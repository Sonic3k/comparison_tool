package com.fpt.comparison_tool.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.comparison_tool.model.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Converts a TestSuite into Postman Collection v2.1 format — 3 files returned as a record:
 *
 *   collectionJson  — all test groups as folders; within each group, TCs are organized
 *                     by Phase (Setup → Test Cases → Teardown sub-folders when mixed,
 *                     or flat when all TCs are the default TEST phase)
 *   sourceEnvJson   — baseUrl + auth variables for the source environment
 *   targetEnvJson   — baseUrl + auth variables for the target environment
 *
 * Auth strategy (collection-level, driven by source env's auth profile):
 *   NONE               → noauth
 *   BEARER             → bearer with {{authToken}}
 *   BASIC              → basic with {{authUsername}} / {{authPassword}}
 *   CLIENT_CREDENTIALS → bearer with {{authToken}}, pre-request script fetches token automatically
 *
 * Note: extractVariables DSL is not exported (Postman has no equivalent concept).
 *       VerificationMode.NONE TCs are exported as normal requests without any assertion.
 */
public class PostmanExporter {

    private static final String SCHEMA =
            "https://schema.getpostman.com/json/collection/v2.1.0/collection.json";

    public record PostmanExport(byte[] collectionJson, byte[] sourceEnvJson, byte[] targetEnvJson) {}

    private final ObjectMapper mapper = new ObjectMapper();

    // ─── Entry point ──────────────────────────────────────────────────────────

    public PostmanExport export(TestSuite suite) throws Exception {
        List<Environment> envs     = nullSafe(suite.getEnvironments());
        List<AuthProfile> profiles = nullSafe(suite.getAuthProfiles());

        Environment sourceEnv  = resolveEnv(envs, suite.getSettings(), true);
        Environment targetEnv  = resolveEnv(envs, suite.getSettings(), false);
        AuthProfile sourceAuth = resolveAuth(profiles, sourceEnv);
        AuthProfile targetAuth = resolveAuth(profiles, targetEnv);

        boolean    needsOauth2      = isOauth2(sourceAuth) || isOauth2(targetAuth);
        AuthType   collectionAuth   = dominantAuthType(sourceAuth);

        byte[] collection = buildCollection(suite, collectionAuth, needsOauth2);
        byte[] sourceFile = buildEnvironment("Source", sourceEnv, sourceAuth);
        byte[] targetFile = buildEnvironment("Target", targetEnv, targetAuth);

        return new PostmanExport(collection, sourceFile, targetFile);
    }

    // ─── Collection ───────────────────────────────────────────────────────────

    private byte[] buildCollection(TestSuite suite, AuthType authType, boolean needsOauth2)
            throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.set("info",     buildInfo(suite));
        root.set("auth",     buildAuth(authType));
        if (needsOauth2) root.set("event", buildOauth2PreRequestEvent());
        root.set("variable", buildCollectionVariables());
        root.set("item",     buildFolders(nullSafe(suite.getTestGroups())));
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
    }

    private ObjectNode buildInfo(TestSuite suite) {
        String name = suite.getSettings() != null ? suite.getSettings().getSuiteName() : null;
        ObjectNode info = mapper.createObjectNode();
        info.put("name",   name != null && !name.isBlank() ? name : "API Comparison Suite");
        info.put("schema", SCHEMA);
        return info;
    }

    // ─── Auth ─────────────────────────────────────────────────────────────────

    private ObjectNode buildAuth(AuthType type) {
        if (type == null) return noauth();
        return switch (type) {
            case BEARER, CLIENT_CREDENTIALS -> {
                ObjectNode auth = mapper.createObjectNode();
                auth.put("type", "bearer");
                ArrayNode bearer = mapper.createArrayNode();
                bearer.add(authVar("token", "{{authToken}}"));
                auth.set("bearer", bearer);
                yield auth;
            }
            case BASIC -> {
                ObjectNode auth = mapper.createObjectNode();
                auth.put("type", "basic");
                ArrayNode basic = mapper.createArrayNode();
                basic.add(authVar("username", "{{authUsername}}"));
                basic.add(authVar("password", "{{authPassword}}"));
                auth.set("basic", basic);
                yield auth;
            }
            default -> noauth();
        };
    }

    /**
     * Pre-request script: when authType variable = "oauth2", fetches a client-credentials
     * token before every request and stores it in authToken.
     */
    private ArrayNode buildOauth2PreRequestEvent() {
        String script = """
                if (pm.environment.get('authType') === 'oauth2') {
                    const tokenUrl     = pm.environment.get('tokenUrl');
                    const clientId     = pm.environment.get('clientId');
                    const clientSecret = pm.environment.get('clientSecret');
                    const scope        = pm.environment.get('scope') || '';

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
                        if (!err) pm.environment.set('authToken', res.json().access_token);
                    });
                }
                """;

        ObjectNode scriptNode = mapper.createObjectNode();
        scriptNode.put("type", "text/javascript");
        ArrayNode exec = mapper.createArrayNode();
        Arrays.stream(script.split("\\n")).forEach(exec::add);
        scriptNode.set("exec", exec);

        ObjectNode event = mapper.createObjectNode();
        event.put("listen", "prerequest");
        event.set("script", scriptNode);

        ArrayNode events = mapper.createArrayNode();
        events.add(event);
        return events;
    }

    private ArrayNode buildCollectionVariables() {
        ArrayNode vars = mapper.createArrayNode();
        ObjectNode baseUrl = mapper.createObjectNode();
        baseUrl.put("key",   "baseUrl");
        baseUrl.put("value", "");
        baseUrl.put("type",  "string");
        vars.add(baseUrl);
        return vars;
    }

    // ─── Folders (TestGroup → Postman folder) ─────────────────────────────────

    private ArrayNode buildFolders(List<TestGroup> groups) {
        ArrayNode folders = mapper.createArrayNode();
        for (TestGroup group : groups) {
            ObjectNode folder = mapper.createObjectNode();
            folder.put("name", group.getName());
            if (group.getDescription() != null && !group.getDescription().isBlank()) {
                folder.put("description", group.getDescription());
            }
            folder.set("item", buildGroupItems(nullSafe(group.getTestCases())));
            folders.add(folder);
        }
        return folders;
    }

    /**
     * If the group contains TCs from multiple phases, organise them into
     * "Setup", "Test Cases", and "Teardown" sub-folders so Postman users
     * can run each phase independently in the correct order.
     *
     * If all TCs are the default TEST phase, keep the list flat — no nesting.
     */
    private ArrayNode buildGroupItems(List<TestCase> testCases) {
        boolean hasMultiplePhases = testCases.stream()
                .map(tc -> tc.getPhase() != null ? tc.getPhase() : Phase.TEST)
                .collect(Collectors.toSet())
                .size() > 1;

        if (!hasMultiplePhases) {
            return buildRequests(testCases);
        }

        // Split by phase and create sub-folders
        ArrayNode items = mapper.createArrayNode();

        List<TestCase> setup    = byPhase(testCases, Phase.SETUP);
        List<TestCase> test     = byPhase(testCases, Phase.TEST);
        List<TestCase> teardown = byPhase(testCases, Phase.TEARDOWN);

        if (!setup.isEmpty())    items.add(subFolder("Setup",      setup));
        if (!test.isEmpty())     items.add(subFolder("Test Cases", test));
        if (!teardown.isEmpty()) items.add(subFolder("Teardown",   teardown));

        return items;
    }

    private ObjectNode subFolder(String name, List<TestCase> testCases) {
        ObjectNode folder = mapper.createObjectNode();
        folder.put("name", name);
        folder.set("item", buildRequests(testCases));
        return folder;
    }

    private List<TestCase> byPhase(List<TestCase> testCases, Phase phase) {
        return testCases.stream()
                .filter(tc -> (tc.getPhase() != null ? tc.getPhase() : Phase.TEST) == phase)
                .collect(Collectors.toList());
    }

    // ─── Requests (TestCase → Postman request item) ───────────────────────────

    private ArrayNode buildRequests(List<TestCase> testCases) {
        ArrayNode items = mapper.createArrayNode();
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
        request.set("url",    buildUrl(tc));
        request.set("header", buildHeaders(tc.getHeaders()));
        ObjectNode body = buildBody(tc);
        if (body != null) request.set("body", body);
        return request;
    }

    private ObjectNode buildUrl(TestCase tc) {
        String endpoint      = tc.getEndpoint() != null ? tc.getEndpoint() : "";
        List<Param> qp       = nullSafe(tc.getQueryParams());
        String rawQueryString = tc.getQueryParamsAsString();

        StringBuilder raw = new StringBuilder("{{baseUrl}}").append(endpoint);
        if (!rawQueryString.isBlank()) raw.append("?").append(rawQueryString);

        ObjectNode url = mapper.createObjectNode();
        url.put("raw", raw.toString());

        ArrayNode host = mapper.createArrayNode();
        host.add("{{baseUrl}}");
        url.set("host", host);

        ArrayNode path = mapper.createArrayNode();
        Arrays.stream(endpoint.split("/"))
              .filter(s -> !s.isBlank())
              .forEach(path::add);
        url.set("path", path);

        if (!qp.isEmpty()) {
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
            ObjectNode h = mapper.createObjectNode();
            h.put("key",   line.substring(0, colon).trim());
            h.put("value", line.substring(colon + 1).trim());
            headers.add(h);
        }
        return headers;
    }

    private ObjectNode buildBody(TestCase tc) {
        List<Param> formParams = nullSafe(tc.getFormParams());
        String jsonBody        = tc.getJsonBody();

        if (!formParams.isEmpty()) {
            ObjectNode body      = mapper.createObjectNode();
            ArrayNode urlencoded = mapper.createArrayNode();
            for (Param p : formParams) {
                ObjectNode entry = mapper.createObjectNode();
                entry.put("key",   p.getKey());
                entry.put("value", p.getValue());
                entry.put("type",  "text");
                urlencoded.add(entry);
            }
            body.put("mode", "urlencoded");
            body.set("urlencoded", urlencoded);
            return body;
        }

        if (jsonBody != null && !jsonBody.isBlank()) {
            ObjectNode body    = mapper.createObjectNode();
            ObjectNode options = mapper.createObjectNode();
            ObjectNode rawOpts = mapper.createObjectNode();
            rawOpts.put("language", "json");
            options.set("raw", rawOpts);
            body.put("mode", "raw");
            body.put("raw",  jsonBody);
            body.set("options", options);
            return body;
        }

        return null;
    }

    // ─── Environment ──────────────────────────────────────────────────────────

    private byte[] buildEnvironment(String label, Environment env, AuthProfile auth)
            throws Exception {
        String envName = env != null && env.getName() != null ? env.getName() : label;
        String baseUrl = env != null && env.getUrl()  != null ? env.getUrl()  : "";

        ArrayNode values = mapper.createArrayNode();
        values.add(envEntry("baseUrl", baseUrl));
        addAuthVariables(values, auth);

        // Default headers from environment — exported as reference variables (header_*)
        if (env != null) {
            for (Param h : nullSafe(env.getHeaders())) {
                values.add(envEntry("header_" + h.getKey().replace("-", "_"), h.getValue()));
            }
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("name", label + " (" + envName + ")");
        root.set("values", values);
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
    }

    private void addAuthVariables(ArrayNode values, AuthProfile auth) {
        if (auth == null || auth.getType() == null || auth.getType() == AuthType.NONE) return;

        switch (auth.getType()) {
            case BEARER -> values.add(envEntry("authToken",
                    notBlank(auth.getToken(), "your-bearer-token")));

            case BASIC -> {
                values.add(envEntry("authUsername", notBlank(auth.getUsername(), "")));
                values.add(envEntry("authPassword", notBlank(auth.getPassword(), "")));
            }

            case CLIENT_CREDENTIALS -> {
                values.add(envEntry("authType",     "oauth2"));
                values.add(envEntry("tokenUrl",     notBlank(auth.getTokenUrl(), "")));
                values.add(envEntry("clientId",     notBlank(auth.getClientId(), "")));
                values.add(envEntry("clientSecret", notBlank(auth.getClientSecret(), "")));
                values.add(envEntry("scope",        notBlank(auth.getScope(), "")));
                values.add(envEntry("authToken",    ""));   // populated at runtime by pre-request script
            }

            default -> { /* SAML — not supported in Postman natively */ }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private ObjectNode envEntry(String key, String value) {
        ObjectNode node = mapper.createObjectNode();
        node.put("key",     key);
        node.put("value",   value);
        node.put("type",    "default");
        node.put("enabled", true);
        return node;
    }

    private ObjectNode authVar(String key, String value) {
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

    private Environment resolveEnv(List<Environment> envs, SuiteSettings settings, boolean isSource) {
        if (envs == null || settings == null || settings.getExecutionConfig() == null) return null;
        String name = isSource
                ? settings.getExecutionConfig().getSourceEnvironment()
                : settings.getExecutionConfig().getTargetEnvironment();
        if (name == null) return null;
        return envs.stream().filter(e -> name.equals(e.getName())).findFirst().orElse(null);
    }

    private AuthProfile resolveAuth(List<AuthProfile> profiles, Environment env) {
        if (env == null || env.getAuthProfile() == null || profiles == null) return null;
        return profiles.stream()
                .filter(p -> env.getAuthProfile().equals(p.getName()))
                .findFirst().orElse(null);
    }

    /** Collection-level auth type is driven by the source environment (most common case). */
    private AuthType dominantAuthType(AuthProfile sourceAuth) {
        if (sourceAuth == null || sourceAuth.getType() == null) return AuthType.NONE;
        return sourceAuth.getType();
    }

    private boolean isOauth2(AuthProfile auth) {
        return auth != null && auth.getType() == AuthType.CLIENT_CREDENTIALS;
    }

    private <T> List<T> nullSafe(List<T> list) {
        return list != null ? list : Collections.emptyList();
    }

    private String notBlank(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }
}
