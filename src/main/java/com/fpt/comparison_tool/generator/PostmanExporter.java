package com.fpt.comparison_tool.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.comparison_tool.model.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Converts a TestSuite into Postman Collection v2.1 format.
 *
 * Two export modes:
 *
 *   exportSingle(suite, isSource)
 *     → single collection.json with baseUrl and auth values hardcoded in
 *       collection variables — no environment file needed.
 *
 *   exportBoth(suite)
 *     → PostmanExport(collection.json, source-env.json, target-env.json)
 *       collection uses {{baseUrl}} / {{authToken}} placeholders;
 *       values are injected via the two environment files.
 *
 * Auth (collection-level, driven by the exported environment's auth profile):
 *   NONE               → noauth
 *   BEARER             → bearer with {{authToken}}
 *   BASIC              → basic with {{authUsername}} / {{authPassword}}
 *   CLIENT_CREDENTIALS → bearer with {{authToken}};
 *                        pre-request script fetches token automatically
 *                        (reads vars via pm.variables.get — works for both modes)
 *
 * Phase-aware folder structure:
 *   Groups with mixed phases → sub-folders Setup / Test Cases / Teardown
 *   Groups with only TEST phase → flat list (no nesting)
 *
 * Note: extractVariables DSL is not exported (no Postman equivalent).
 */
public class PostmanExporter {

    private static final String SCHEMA =
            "https://schema.getpostman.com/json/collection/v2.1.0/collection.json";

    public record PostmanExport(byte[] collectionJson, byte[] sourceEnvJson, byte[] targetEnvJson) {}

    private final ObjectMapper mapper = new ObjectMapper();

    // ─── Public entry points ──────────────────────────────────────────────────

    /**
     * Single-env export: collection variables hold the actual URL and auth values.
     * No environment file required — importable as a standalone collection.
     */
    public byte[] exportSingle(TestSuite suite, boolean isSource) throws Exception {
        List<Environment> envs     = nullSafe(suite.getEnvironments());
        List<AuthProfile> profiles = nullSafe(suite.getAuthProfiles());

        Environment env  = resolveEnv(envs, suite.getSettings(), isSource);
        AuthProfile auth = resolveAuth(profiles, env);

        return buildCollection(suite, dominantAuthType(auth), isOauth2(auth),
                               buildSingleModeVariables(env, auth));
    }

    /**
     * Dual-env export: collection uses {{baseUrl}} / auth placeholders.
     * Returns collection + two environment files (source and target).
     */
    public PostmanExport exportBoth(TestSuite suite) throws Exception {
        List<Environment> envs     = nullSafe(suite.getEnvironments());
        List<AuthProfile> profiles = nullSafe(suite.getAuthProfiles());

        Environment sourceEnv  = resolveEnv(envs, suite.getSettings(), true);
        Environment targetEnv  = resolveEnv(envs, suite.getSettings(), false);
        AuthProfile sourceAuth = resolveAuth(profiles, sourceEnv);
        AuthProfile targetAuth = resolveAuth(profiles, targetEnv);

        boolean needsOauth2 = isOauth2(sourceAuth) || isOauth2(targetAuth);

        byte[] collection = buildCollection(suite, dominantAuthType(sourceAuth), needsOauth2,
                                            buildBothModeBaseVariable());
        byte[] sourceFile = buildEnvironmentFile("Source", sourceEnv, sourceAuth);
        byte[] targetFile = buildEnvironmentFile("Target", targetEnv, targetAuth);

        return new PostmanExport(collection, sourceFile, targetFile);
    }

    // ─── Collection ───────────────────────────────────────────────────────────

    private byte[] buildCollection(TestSuite suite, AuthType authType,
                                   boolean needsOauth2, ArrayNode collectionVariables)
            throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.set("info",     buildInfo(suite));
        root.set("auth",     buildAuth(authType));
        if (needsOauth2) root.set("event", buildOauth2PreRequestEvent());
        root.set("variable", collectionVariables);
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

    // ─── Collection variables ─────────────────────────────────────────────────

    /** Single mode: baseUrl + auth values hardcoded so the collection is self-contained. */
    private ArrayNode buildSingleModeVariables(Environment env, AuthProfile auth) {
        ArrayNode vars = mapper.createArrayNode();
        vars.add(collectionVar("baseUrl", env != null && env.getUrl() != null ? env.getUrl() : ""));
        addAuthCollectionVars(vars, auth);
        return vars;
    }

    /** Both mode: baseUrl is empty — actual value comes from the environment file. */
    private ArrayNode buildBothModeBaseVariable() {
        ArrayNode vars = mapper.createArrayNode();
        vars.add(collectionVar("baseUrl", ""));
        return vars;
    }

    private void addAuthCollectionVars(ArrayNode vars, AuthProfile auth) {
        if (auth == null || auth.getType() == null || auth.getType() == AuthType.NONE) return;
        switch (auth.getType()) {
            case BEARER ->
                vars.add(collectionVar("authToken", notBlank(auth.getToken(), "your-bearer-token")));
            case BASIC -> {
                vars.add(collectionVar("authUsername", notBlank(auth.getUsername(), "")));
                vars.add(collectionVar("authPassword", notBlank(auth.getPassword(), "")));
            }
            case CLIENT_CREDENTIALS -> {
                vars.add(collectionVar("authType",     "oauth2"));
                vars.add(collectionVar("tokenUrl",     notBlank(auth.getTokenUrl(), "")));
                vars.add(collectionVar("clientId",     notBlank(auth.getClientId(), "")));
                vars.add(collectionVar("clientSecret", notBlank(auth.getClientSecret(), "")));
                vars.add(collectionVar("scope",        notBlank(auth.getScope(), "")));
                vars.add(collectionVar("authToken",    ""));  // populated at runtime
            }
            default -> { /* SAML — not supported */ }
        }
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
     * Pre-request script: fetches OAuth2 client-credentials token when authType = "oauth2".
     * Uses pm.variables.get() which resolves environment → collection variables → globals,
     * so this works for both single-env and dual-env (both) export modes.
     */
    private ArrayNode buildOauth2PreRequestEvent() {
        String script =
            "const getVar = key => pm.variables.get(key);\n" +
            "\n" +
            "if (getVar('authType') === 'oauth2') {\n" +
            "    const tokenUrl     = getVar('tokenUrl');\n" +
            "    const clientId     = getVar('clientId');\n" +
            "    const clientSecret = getVar('clientSecret');\n" +
            "    const scope        = getVar('scope') || '';\n" +
            "\n" +
            "    const body = 'grant_type=client_credentials'\n" +
            "        + '&client_id='     + encodeURIComponent(clientId)\n" +
            "        + '&client_secret=' + encodeURIComponent(clientSecret)\n" +
            "        + (scope ? '&scope=' + encodeURIComponent(scope) : '');\n" +
            "\n" +
            "    pm.sendRequest({\n" +
            "        url:    tokenUrl,\n" +
            "        method: 'POST',\n" +
            "        header: { 'Content-Type': 'application/x-www-form-urlencoded' },\n" +
            "        body:   { mode: 'raw', raw: body }\n" +
            "    }, (err, res) => {\n" +
            "        if (!err) pm.variables.set('authToken', res.json().access_token);\n" +
            "    });\n" +
            "}";

        ObjectNode scriptNode = mapper.createObjectNode();
        scriptNode.put("type", "text/javascript");
        ArrayNode exec = mapper.createArrayNode();
        Arrays.stream(script.split("\n")).forEach(exec::add);
        scriptNode.set("exec", exec);

        ObjectNode event = mapper.createObjectNode();
        event.put("listen", "prerequest");
        event.set("script", scriptNode);

        ArrayNode events = mapper.createArrayNode();
        events.add(event);
        return events;
    }

    // ─── Environment file (both mode only) ───────────────────────────────────

    private byte[] buildEnvironmentFile(String label, Environment env, AuthProfile auth)
            throws Exception {
        String envName = env != null && env.getName() != null ? env.getName() : label;
        String baseUrl = env != null && env.getUrl()  != null ? env.getUrl()  : "";

        ArrayNode values = mapper.createArrayNode();
        values.add(envEntry("baseUrl", baseUrl));
        addAuthEnvVars(values, auth);

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

    private void addAuthEnvVars(ArrayNode values, AuthProfile auth) {
        if (auth == null || auth.getType() == null || auth.getType() == AuthType.NONE) return;
        switch (auth.getType()) {
            case BEARER ->
                values.add(envEntry("authToken", notBlank(auth.getToken(), "your-bearer-token")));
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
                values.add(envEntry("authToken",    ""));
            }
            default -> { /* SAML — not supported */ }
        }
    }

    // ─── Folders & Requests ───────────────────────────────────────────────────

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

    private ArrayNode buildGroupItems(List<TestCase> testCases) {
        boolean hasMultiplePhases = testCases.stream()
                .map(tc -> tc.getPhase() != null ? tc.getPhase() : Phase.TEST)
                .collect(Collectors.toSet())
                .size() > 1;

        if (!hasMultiplePhases) return buildRequests(testCases);

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

    private List<TestCase> byPhase(List<TestCase> tcs, Phase phase) {
        return tcs.stream()
                .filter(tc -> (tc.getPhase() != null ? tc.getPhase() : Phase.TEST) == phase)
                .collect(Collectors.toList());
    }

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
        String      endpoint = tc.getEndpoint() != null ? tc.getEndpoint() : "";
        List<Param> qp       = nullSafe(tc.getQueryParams());
        String      rawQs    = tc.getQueryParamsAsString();

        StringBuilder raw = new StringBuilder("{{baseUrl}}").append(endpoint);
        if (!rawQs.isBlank()) raw.append("?").append(rawQs);

        ObjectNode url = mapper.createObjectNode();
        url.put("raw", raw.toString());

        ArrayNode host = mapper.createArrayNode();
        host.add("{{baseUrl}}");
        url.set("host", host);

        ArrayNode path = mapper.createArrayNode();
        Arrays.stream(endpoint.split("/")).filter(s -> !s.isBlank()).forEach(path::add);
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
        String      jsonBody   = tc.getJsonBody();

        if (!formParams.isEmpty()) {
            ArrayNode urlencoded = mapper.createArrayNode();
            for (Param p : formParams) {
                ObjectNode e = mapper.createObjectNode();
                e.put("key",   p.getKey());
                e.put("value", p.getValue());
                e.put("type",  "text");
                urlencoded.add(e);
            }
            ObjectNode body = mapper.createObjectNode();
            body.put("mode", "urlencoded");
            body.set("urlencoded", urlencoded);
            return body;
        }

        if (jsonBody != null && !jsonBody.isBlank()) {
            ObjectNode rawOpts = mapper.createObjectNode();
            rawOpts.put("language", "json");
            ObjectNode options = mapper.createObjectNode();
            options.set("raw", rawOpts);
            ObjectNode body = mapper.createObjectNode();
            body.put("mode", "raw");
            body.put("raw",  jsonBody);
            body.set("options", options);
            return body;
        }

        return null;
    }

    // ─── Node builders ────────────────────────────────────────────────────────

    private ObjectNode collectionVar(String key, String value) {
        ObjectNode node = mapper.createObjectNode();
        node.put("key",   key);
        node.put("value", value);
        node.put("type",  "string");
        return node;
    }

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

    // ─── Lookup helpers ───────────────────────────────────────────────────────

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

    private AuthType dominantAuthType(AuthProfile auth) {
        if (auth == null || auth.getType() == null) return AuthType.NONE;
        return auth.getType();
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
