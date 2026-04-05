package com.fpt.comparison_tool.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.comparison_tool.model.*;

import java.util.*;

/**
 * Converts a TestSuite into Postman Collection v2.1 format:
 *   - collection.json  → all test groups as folders, requests use {{baseUrl}}
 *   - source-env.json  → base URL + auth variables for the source environment
 *   - target-env.json  → base URL + auth variables for the target environment
 *
 * Auth strategy (collection-level):
 *   NONE               → noauth
 *   BEARER             → bearer with {{authToken}}
 *   BASIC              → basic with {{authUsername}} / {{authPassword}}
 *   CLIENT_CREDENTIALS → bearer with {{authToken}}, pre-request script fetches token
 */
public class PostmanExporter {

    private static final String SCHEMA =
            "https://schema.getpostman.com/json/collection/v2.1.0/collection.json";

    public record PostmanExport(byte[] collectionJson, byte[] sourceEnvJson, byte[] targetEnvJson) {}

    private final ObjectMapper mapper = new ObjectMapper();

    // ─── Public API ────────────────────────────────────────────────────────────

    public PostmanExport export(TestSuite suite) throws Exception {

        List<Environment> envs   = nullSafe(suite.getEnvironments());
        List<AuthProfile> profiles = nullSafe(suite.getAuthProfiles());

        Environment sourceEnv  = findEnv(envs, suite.getSettings(), true);
        Environment targetEnv  = findEnv(envs, suite.getSettings(), false);
        AuthProfile sourceAuth = findAuth(profiles, sourceEnv);
        AuthProfile targetAuth = findAuth(profiles, targetEnv);

        boolean needsOauth2 = isOauth2(sourceAuth) || isOauth2(targetAuth);
        AuthType collectionAuthType = resolveCollectionAuthType(sourceAuth);

        byte[] collection = buildCollection(suite, collectionAuthType, needsOauth2);
        byte[] sourceFile = buildEnvironment("Source", sourceEnv, sourceAuth);
        byte[] targetFile = buildEnvironment("Target", targetEnv, targetAuth);

        return new PostmanExport(collection, sourceFile, targetFile);
    }

    // ─── Collection ────────────────────────────────────────────────────────────

    private byte[] buildCollection(TestSuite suite,
                                   AuthType collectionAuthType,
                                   boolean needsOauth2) throws Exception {
        ObjectNode root = mapper.createObjectNode();

        root.set("info", buildInfo(suite));
        root.set("auth", buildCollectionAuth(collectionAuthType));

        if (needsOauth2) {
            root.set("event", buildOauth2PreRequestEvent());
        }

        root.set("variable", buildCollectionVariables());
        root.set("item", buildFolders(suite.getTestGroups()));

        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
    }

    private ObjectNode buildInfo(TestSuite suite) {
        String name = suite.getSettings() != null
                ? suite.getSettings().getSuiteName()
                : "API Comparison Suite";

        ObjectNode info = mapper.createObjectNode();
        info.put("name", name != null ? name : "API Comparison Suite");
        info.put("schema", SCHEMA);
        return info;
    }

    private ObjectNode buildCollectionAuth(AuthType type) {
        if (type == null) return noauth();
        return switch (type) {
            case BEARER, CLIENT_CREDENTIALS -> {
                ObjectNode auth = mapper.createObjectNode();
                auth.put("type", "bearer");
                ArrayNode bearer = mapper.createArrayNode();
                bearer.add(envVar("token", "{{authToken}}"));
                auth.set("bearer", bearer);
                yield auth;
            }
            case BASIC -> {
                ObjectNode auth = mapper.createObjectNode();
                auth.put("type", "basic");
                ArrayNode basic = mapper.createArrayNode();
                basic.add(envVar("username", "{{authUsername}}"));
                basic.add(envVar("password", "{{authPassword}}"));
                auth.set("basic", basic);
                yield auth;
            }
            default -> noauth();
        };
    }

    /** Pre-request script executed before every request — fetches OAuth2 token when authType=oauth2 */
    private ArrayNode buildOauth2PreRequestEvent() {
        String script = """
                if (pm.environment.get('authType') === 'oauth2') {
                    const tokenUrl    = pm.environment.get('tokenUrl');
                    const clientId    = pm.environment.get('clientId');
                    const clientSecret = pm.environment.get('clientSecret');
                    const scope       = pm.environment.get('scope') || '';

                    const body = 'grant_type=client_credentials'
                        + '&client_id='     + encodeURIComponent(clientId)
                        + '&client_secret=' + encodeURIComponent(clientSecret)
                        + (scope ? '&scope=' + encodeURIComponent(scope) : '');

                    pm.sendRequest({
                        url:    tokenUrl,
                        method: 'POST',
                        header: { 'Content-Type': 'application/x-www-form-urlencoded' },
                        body:   { mode: 'raw', raw: body }
                    }, (err, res) => {
                        if (!err) {
                            pm.environment.set('authToken', res.json().access_token);
                        }
                    });
                }
                """;

        ObjectNode event = mapper.createObjectNode();
        event.put("listen", "prerequest");

        ObjectNode scriptNode = mapper.createObjectNode();
        scriptNode.put("type", "text/javascript");
        ArrayNode exec = mapper.createArrayNode();
        Arrays.stream(script.split("\\n")).forEach(exec::add);
        scriptNode.set("exec", exec);

        event.set("script", scriptNode);

        ArrayNode events = mapper.createArrayNode();
        events.add(event);
        return events;
    }

    private ArrayNode buildCollectionVariables() {
        ArrayNode vars = mapper.createArrayNode();
        ObjectNode baseUrl = mapper.createObjectNode();
        baseUrl.put("key", "baseUrl");
        baseUrl.put("value", "");
        baseUrl.put("type", "string");
        vars.add(baseUrl);
        return vars;
    }

    // ─── Folders (TestGroup → Postman folder) ─────────────────────────────────

    private ArrayNode buildFolders(List<TestGroup> groups) {
        ArrayNode folders = mapper.createArrayNode();
        if (groups == null) return folders;

        for (TestGroup group : groups) {
            ObjectNode folder = mapper.createObjectNode();
            folder.put("name", group.getName());

            if (group.getDescription() != null && !group.getDescription().isBlank()) {
                folder.put("description", group.getDescription());
            }

            folder.set("item", buildRequests(group.getTestCases()));
            folders.add(folder);
        }
        return folders;
    }

    // ─── Requests (TestCase → Postman request) ────────────────────────────────

    private ArrayNode buildRequests(List<TestCase> testCases) {
        ArrayNode items = mapper.createArrayNode();
        if (testCases == null) return items;

        for (TestCase tc : testCases) {
            ObjectNode item = mapper.createObjectNode();
            item.put("name", tc.getName() != null ? tc.getName() : tc.getId());

            if (tc.getDescription() != null && !tc.getDescription().isBlank()) {
                item.put("description", tc.getDescription());
            }

            item.set("request", buildRequest(tc));
            items.add(item);
        }
        return items;
    }

    private ObjectNode buildRequest(TestCase tc) {
        ObjectNode request = mapper.createObjectNode();

        request.put("method", tc.getMethod() != null ? tc.getMethod().name() : "GET");
        request.set("url", buildUrl(tc));
        request.set("header", buildHeaders(tc.getHeaders()));

        ObjectNode body = buildBody(tc);
        if (body != null) request.set("body", body);

        return request;
    }

    private ObjectNode buildUrl(TestCase tc) {
        String endpoint   = tc.getEndpoint() != null ? tc.getEndpoint() : "";
        List<Param> qp    = tc.getQueryParams();

        StringBuilder raw = new StringBuilder("{{baseUrl}}").append(endpoint);
        if (qp != null && !qp.isEmpty()) {
            raw.append("?");
            raw.append(tc.getQueryParamsAsString());
        }

        ObjectNode url = mapper.createObjectNode();
        url.put("raw", raw.toString());

        // host
        ArrayNode host = mapper.createArrayNode();
        host.add("{{baseUrl}}");
        url.set("host", host);

        // path — split endpoint by "/"
        ArrayNode path = mapper.createArrayNode();
        if (!endpoint.isBlank()) {
            Arrays.stream(endpoint.split("/"))
                  .filter(s -> !s.isBlank())
                  .forEach(path::add);
        }
        url.set("path", path);

        // query
        if (qp != null && !qp.isEmpty()) {
            ArrayNode query = mapper.createArrayNode();
            for (Param p : qp) {
                ObjectNode q = mapper.createObjectNode();
                q.put("key",   p.getKey());
                q.put("value", p.getValue());
                query.add(q);
            }
            url.set("query", query);
        }

        return url;
    }

    private ArrayNode buildHeaders(String rawHeaders) {
        ArrayNode headers = mapper.createArrayNode();
        if (rawHeaders == null || rawHeaders.isBlank()) return headers;

        for (String line : rawHeaders.split("\\n")) {
            int colon = line.indexOf(":");
            if (colon <= 0) continue;
            String key   = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            ObjectNode h = mapper.createObjectNode();
            h.put("key",   key);
            h.put("value", value);
            headers.add(h);
        }
        return headers;
    }

    private ObjectNode buildBody(TestCase tc) {
        List<Param> formParams = tc.getFormParams();
        String jsonBody        = tc.getJsonBody();

        if (formParams != null && !formParams.isEmpty()) {
            ObjectNode body = mapper.createObjectNode();
            body.put("mode", "urlencoded");
            ArrayNode urlencoded = mapper.createArrayNode();
            for (Param p : formParams) {
                ObjectNode entry = mapper.createObjectNode();
                entry.put("key",   p.getKey());
                entry.put("value", p.getValue());
                entry.put("type",  "text");
                urlencoded.add(entry);
            }
            body.set("urlencoded", urlencoded);
            return body;
        }

        if (jsonBody != null && !jsonBody.isBlank()) {
            ObjectNode body = mapper.createObjectNode();
            body.put("mode", "raw");
            body.put("raw",  jsonBody);
            ObjectNode options = mapper.createObjectNode();
            ObjectNode raw     = mapper.createObjectNode();
            raw.put("language", "json");
            options.set("raw", raw);
            body.set("options", options);
            return body;
        }

        return null;
    }

    // ─── Environment ───────────────────────────────────────────────────────────

    private byte[] buildEnvironment(String label, Environment env, AuthProfile auth) throws Exception {
        String name    = env != null ? env.getName() : label;
        String baseUrl = env != null && env.getUrl() != null ? env.getUrl() : "";

        ObjectNode root = mapper.createObjectNode();
        root.put("name", label + " (" + name + ")");

        ArrayNode values = mapper.createArrayNode();
        values.add(envEntry("baseUrl", baseUrl));
        addAuthVariables(values, auth);

        // Default headers from environment (as informational variables)
        if (env != null && env.getHeaders() != null) {
            for (Param h : env.getHeaders()) {
                values.add(envEntry("header_" + h.getKey().replace("-", "_"), h.getValue()));
            }
        }

        root.set("values", values);
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
    }

    private void addAuthVariables(ArrayNode values, AuthProfile auth) {
        if (auth == null || auth.getType() == null || auth.getType() == AuthType.NONE) {
            return;
        }

        switch (auth.getType()) {
            case BEARER -> values.add(envEntry("authToken",
                    notNull(auth.getToken(), "your-bearer-token")));

            case BASIC -> {
                values.add(envEntry("authUsername", notNull(auth.getUsername(), "")));
                values.add(envEntry("authPassword", notNull(auth.getPassword(), "")));
            }

            case CLIENT_CREDENTIALS -> {
                values.add(envEntry("authType",     "oauth2"));
                values.add(envEntry("tokenUrl",     notNull(auth.getTokenUrl(), "")));
                values.add(envEntry("clientId",     notNull(auth.getClientId(), "")));
                values.add(envEntry("clientSecret", notNull(auth.getClientSecret(), "")));
                values.add(envEntry("scope",        notNull(auth.getScope(), "")));
                values.add(envEntry("authToken",    ""));  // populated at runtime by pre-request script
            }

            default -> { /* SAML — skip, already filtered out */ }
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private ObjectNode envEntry(String key, String value) {
        ObjectNode node = mapper.createObjectNode();
        node.put("key",     key);
        node.put("value",   value);
        node.put("type",    "default");
        node.put("enabled", true);
        return node;
    }

    /** Postman auth variable entry format */
    private ObjectNode envVar(String key, String value) {
        ObjectNode node = mapper.createObjectNode();
        node.put("key",   key);
        node.put("value", value);
        return node;
    }

    private ObjectNode noauth() {
        ObjectNode auth = mapper.createObjectNode();
        auth.put("type", "noauth");
        return auth;
    }

    private Environment findEnv(List<Environment> envs, SuiteSettings settings, boolean isSource) {
        if (envs == null || settings == null || settings.getExecutionConfig() == null) return null;
        String name = isSource
                ? settings.getExecutionConfig().getSourceEnvironment()
                : settings.getExecutionConfig().getTargetEnvironment();
        if (name == null) return null;
        return envs.stream().filter(e -> name.equals(e.getName())).findFirst().orElse(null);
    }

    private AuthProfile findAuth(List<AuthProfile> profiles, Environment env) {
        if (env == null || env.getAuthProfile() == null || profiles == null) return null;
        return profiles.stream()
                .filter(p -> env.getAuthProfile().equals(p.getName()))
                .findFirst().orElse(null);
    }

    /** Determine collection-level auth type from the source env's profile (fallback: NONE). */
    private AuthType resolveCollectionAuthType(AuthProfile sourceAuth) {
        if (sourceAuth == null) return AuthType.NONE;
        return sourceAuth.getType() != null ? sourceAuth.getType() : AuthType.NONE;
    }

    private boolean isOauth2(AuthProfile auth) {
        return auth != null && auth.getType() == AuthType.CLIENT_CREDENTIALS;
    }

    private <T> List<T> nullSafe(List<T> list) {
        return list != null ? list : Collections.emptyList();
    }

    private String notNull(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }
}
