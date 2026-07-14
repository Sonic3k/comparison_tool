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
 * extractVariables are exported as per-request test scripts writing to
 * collection variables, so {{var}} chaining works inside Postman/Newman.
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

        ArrayNode vars = buildSingleModeVariables(env, auth);
        overrideAuthVars(suite).forEach(vars::add);
        globalVarNodes(suite).forEach(vars::add);
        return buildCollection(suite, dominantAuthType(auth), isOauth2(auth), vars,
                               env != null ? env.getHeaders() : null, profileMap(suite));
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

        ArrayNode bothVars = buildBothModeBaseVariable();
        overrideAuthVars(suite).forEach(bothVars::add);
        globalVarNodes(suite).forEach(bothVars::add);
        byte[] collection = buildCollection(suite, dominantAuthType(sourceAuth), needsOauth2,
                                            bothVars,
                                            unionEnvHeaders(sourceEnv, targetEnv), profileMap(suite));
        byte[] sourceFile = buildEnvironmentFile("Source", sourceEnv, sourceAuth);
        byte[] targetFile = buildEnvironmentFile("Target", targetEnv, targetAuth);

        return new PostmanExport(collection, sourceFile, targetFile);
    }

    // ─── Collection ───────────────────────────────────────────────────────────

    private byte[] buildCollection(TestSuite suite, AuthType authType,
                                   boolean needsOauth2, ArrayNode collectionVariables,
                                   List<Param> envHeaders, Map<String, AuthProfile> profiles)
            throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.set("info",     buildInfo(suite));
        root.set("auth",     buildAuth(authType));
        if (needsOauth2) root.set("event", buildOauth2PreRequestEvent());
        root.set("variable", collectionVariables);
        root.set("item",     buildFolders(nullSafe(suite.getTestGroups()), envHeaders, profiles));
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
        if (env != null) {
            for (Param h : nullSafe(env.getHeaders())) {
                if (h.getKey() == null || h.getKey().isBlank()) continue;
                vars.add(collectionVar(headerVar(h.getKey()), h.getValue()));
            }
            for (Param v : nullSafe(env.getVariables())) {
                if (v.getKey() != null && !v.getKey().isBlank()) vars.add(collectionVar(v.getKey().trim(), v.getValue()));
            }
        }
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
                values.add(envEntry(headerVar(h.getKey()), h.getValue()));
            }
            // Environment variables, verbatim — {{name}} in requests resolves per env
            for (Param v : nullSafe(env.getVariables())) {
                if (v.getKey() != null && !v.getKey().isBlank()) values.add(envEntry(v.getKey().trim(), v.getValue()));
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

    private ArrayNode buildFolders(List<TestGroup> groups, List<Param> envHeaders, Map<String, AuthProfile> profiles) {
        ArrayNode folders = mapper.createArrayNode();
        for (TestGroup group : groups) {
            ObjectNode folder = mapper.createObjectNode();
            folder.put("name", group.getName());
            if (group.getDescription() != null && !group.getDescription().isBlank()) {
                folder.put("description", group.getDescription());
            }
            folder.set("item", buildGroupItems(nullSafe(group.getTestRequests()), envHeaders, profiles));
            folders.add(folder);
        }
        return folders;
    }

    private ArrayNode buildGroupItems(List<TestRequest> testRequests, List<Param> envHeaders, Map<String, AuthProfile> profiles) {
        boolean hasMultiplePhases = testRequests.stream()
                .map(tc -> tc.getPhase() != null ? tc.getPhase() : Phase.TEST)
                .collect(Collectors.toSet())
                .size() > 1;

        if (!hasMultiplePhases) return buildTestItems(testRequests, envHeaders, profiles);

        ArrayNode items = mapper.createArrayNode();
        List<TestRequest> setup    = byPhase(testRequests, Phase.SETUP);
        List<TestRequest> test     = byPhase(testRequests, Phase.TEST);
        List<TestRequest> teardown = byPhase(testRequests, Phase.TEARDOWN);
        if (!setup.isEmpty())    items.add(subFolder("Setup",      setup,    envHeaders, profiles));
        if (!test.isEmpty()) {
            ObjectNode testFolder = mapper.createObjectNode();
            testFolder.put("name", "Test Cases");
            testFolder.set("item", buildTestItems(test, envHeaders, profiles));
            items.add(testFolder);
        }
        if (!teardown.isEmpty()) items.add(subFolder("Teardown",   teardown, envHeaders, profiles));
        return items;
    }

    /**
     * TEST-phase items grouped by test case: a test case with several member
     * requests becomes a sub-folder (requests kept in declared order); a
     * 1-request test case stays a flat item — avoids one folder per request.
     */
    private ArrayNode buildTestItems(List<TestRequest> testRequests, List<Param> envHeaders, Map<String, AuthProfile> profiles) {
        Map<String, List<TestRequest>> byTc = new LinkedHashMap<>();
        for (TestRequest tc : testRequests) {
            String tcId = tc.getTestCaseId() != null && !tc.getTestCaseId().isBlank()
                    ? tc.getTestCaseId() : tc.getId();
            byTc.computeIfAbsent(tcId, k -> new ArrayList<>()).add(tc);
        }
        ArrayNode items = mapper.createArrayNode();
        for (Map.Entry<String, List<TestRequest>> e : byTc.entrySet()) {
            if (e.getValue().size() > 1) {
                items.add(subFolder(e.getKey(), e.getValue(), envHeaders, profiles));
            } else {
                items.addAll(buildRequests(e.getValue(), envHeaders, profiles));
            }
        }
        return items;
    }

    private ObjectNode subFolder(String name, List<TestRequest> testRequests, List<Param> envHeaders, Map<String, AuthProfile> profiles) {
        ObjectNode folder = mapper.createObjectNode();
        folder.put("name", name);
        folder.set("item", buildRequests(testRequests, envHeaders, profiles));
        return folder;
    }

    private List<TestRequest> byPhase(List<TestRequest> tcs, Phase phase) {
        return tcs.stream()
                .filter(tc -> (tc.getPhase() != null ? tc.getPhase() : Phase.TEST) == phase)
                .collect(Collectors.toList());
    }

    private ArrayNode buildRequests(List<TestRequest> testRequests, List<Param> envHeaders, Map<String, AuthProfile> profiles) {
        ArrayNode items = mapper.createArrayNode();
        for (TestRequest tc : testRequests) {
            ObjectNode item = mapper.createObjectNode();
            item.put("name", tc.getName() != null ? tc.getName() : tc.getId());
            if (tc.getDescription() != null && !tc.getDescription().isBlank()) {
                item.put("description", tc.getDescription());
            }
            item.set("request", buildRequest(tc, envHeaders, profiles));
            ArrayNode ev = buildExtractEvent(tc);
            if (ev != null) item.set("event", ev);
            items.add(item);
        }
        return items;
    }

    /**
     * extractVariables ("name=$.json.path" per line) → Postman test script that
     * stores values in collection variables, so later requests using {{name}}
     * work when the exported collection runs in Postman/Newman.
     * Simple dot / [index] paths are converted; anything fancier is emitted as
     * a TODO comment instead of broken JS.
     */
    private ArrayNode buildExtractEvent(TestRequest tc) {
        String raw = tc.getExtractVariables();
        if (raw == null || raw.isBlank()) return null;

        StringBuilder js = new StringBuilder("var d = {};\ntry { d = pm.response.json(); } catch (e) {}\n");
        boolean any = false;
        for (String line : raw.split("\n")) {
            line = line.trim();
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String name = line.substring(0, eq).trim();
            String path = line.substring(eq + 1).trim();
            if (path.matches("\\$(\\.[A-Za-z0-9_]+|\\[\\d+])*")) {
                String expr = path.equals("$") ? "d" : "d" + path.substring(1);
                js.append("try { pm.collectionVariables.set(\"").append(name)
                  .append("\", ").append(expr).append("); } catch (e) {}\n");
                any = true;
            } else {
                js.append("// TODO unsupported extract path for ").append(name)
                  .append(": ").append(path).append("\n");
            }
        }
        if (!any) return null;

        ObjectNode script = mapper.createObjectNode();
        script.put("type", "text/javascript");
        ArrayNode exec = mapper.createArrayNode();
        Arrays.stream(js.toString().split("\n")).forEach(exec::add);
        script.set("exec", exec);

        ObjectNode event = mapper.createObjectNode();
        event.put("listen", "test");
        event.set("script", script);
        ArrayNode events = mapper.createArrayNode();
        events.add(event);
        return events;
    }

    private ObjectNode buildRequest(TestRequest tc, List<Param> envHeaders, Map<String, AuthProfile> profiles) {
        ObjectNode request = mapper.createObjectNode();
        request.put("method", tc.getMethod() != null ? tc.getMethod().name() : "GET");
        request.set("url",    buildUrl(tc));
        request.set("header", buildHeaders(tc, envHeaders));
        ObjectNode overrideAuth = overrideAuth(tc, profiles);
        if (overrideAuth != null) request.set("auth", overrideAuth);
        ObjectNode body = buildBody(tc);
        if (body != null) request.set("body", body);
        return request;
    }

    private ObjectNode buildUrl(TestRequest tc) {
        String      endpoint = tc.getEndpoint() != null ? tc.getEndpoint() : "";
        List<Param> qp       = nullSafe(tc.getQueryParams());
        String      rawQs    = tc.getQueryParamsAsString();

        boolean fullUrl = endpoint.startsWith("http://") || endpoint.startsWith("https://")
                       || endpoint.startsWith("{{");
        StringBuilder raw = new StringBuilder(fullUrl ? "" : "{{baseUrl}}").append(endpoint);
        if (!rawQs.isBlank()) raw.append("?").append(rawQs);

        ObjectNode url = mapper.createObjectNode();
        url.put("raw", raw.toString());
        if (fullUrl) {
            // Postman re-parses "raw" on import; host/path splitting below
            // assumes a {{baseUrl}} prefix, so skip it for full URLs.
            if (!qp.isEmpty()) {
                ArrayNode query = mapper.createArrayNode();
                for (Param p : qp) {
                    ObjectNode q = mapper.createObjectNode();
                    q.put("key", p.getKey()); q.put("value", p.getValue());
                    query.add(q);
                }
                url.set("query", query);
            }
            return url;
        }

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

    /**
     * Request headers = the request's own headers (always win) + the
     * environment default headers the engine applies to every call, referenced
     * as {{header_*}} variables so one collection works for both environments.
     * Content-Type follows the engine's rule: a body drives it (Postman sets
     * application/json / urlencoded from the body mode), otherwise the env CT
     * applies; an explicit CT in the request's own headers always wins.
     */
    /**
     * Request-level auth override as a Postman request auth block. Bearer and
     * Basic reference auth_<profile>_* collection variables; other types
     * inherit the collection auth (client_credentials pre-request fetches only
     * the environment's token).
     */
    private ObjectNode overrideAuth(TestRequest tc, Map<String, AuthProfile> profiles) {
        if (tc.getAuthProfile() == null || tc.getAuthProfile().isBlank() || profiles == null) return null;
        AuthProfile p = profiles.get(tc.getAuthProfile());
        if (p == null || p.getType() == null) return null;
        String n = varSafe(p.getName());
        switch (p.getType()) {
            case BEARER -> {
                ObjectNode auth = mapper.createObjectNode();
                auth.put("type", "bearer");
                ArrayNode b = auth.putArray("bearer");
                ObjectNode t = b.addObject();
                t.put("key", "token"); t.put("value", "{{auth_" + n + "_token}}"); t.put("type", "string");
                return auth;
            }
            case BASIC -> {
                ObjectNode auth = mapper.createObjectNode();
                auth.put("type", "basic");
                ArrayNode b = auth.putArray("basic");
                ObjectNode u = b.addObject();
                u.put("key", "username"); u.put("value", "{{auth_" + n + "_user}}"); u.put("type", "string");
                ObjectNode pw = b.addObject();
                pw.put("key", "password"); pw.put("value", "{{auth_" + n + "_pass}}"); pw.put("type", "string");
                return auth;
            }
            case NONE -> {
                ObjectNode auth = mapper.createObjectNode();
                auth.put("type", "noauth");
                return auth;
            }
            default -> { return null; }
        }
    }

    private static String varSafe(String name) {
        return name == null ? "" : name.replaceAll("[^A-Za-z0-9_]", "_");
    }

    /** Collection variables backing every auth override used by any request. */
    private List<ObjectNode> overrideAuthVars(TestSuite suite) {
        Map<String, AuthProfile> profiles = profileMap(suite);
        List<ObjectNode> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (TestGroup g : nullSafe(suite.getTestGroups())) {
            for (TestRequest tc : nullSafe(g.getTestRequests())) {
                String ap = tc.getAuthProfile();
                if (ap == null || ap.isBlank() || !done.add(ap)) continue;
                AuthProfile p = profiles.get(ap);
                if (p == null || p.getType() == null) continue;
                String n = varSafe(p.getName());
                switch (p.getType()) {
                    case BEARER -> out.add(collectionVar("auth_" + n + "_token", notBlank(p.getToken(), "")));
                    case BASIC -> {
                        out.add(collectionVar("auth_" + n + "_user", notBlank(p.getUsername(), "")));
                        out.add(collectionVar("auth_" + n + "_pass", notBlank(p.getPassword(), "")));
                    }
                    default -> { }
                }
            }
        }
        return out;
    }

    /** Session globals as collection variables — bare names, work in single and both mode. */
    private List<ObjectNode> globalVarNodes(TestSuite suite) {
        List<ObjectNode> out = new ArrayList<>();
        if (suite.getGlobalVariables() != null) {
            for (GlobalVariable v : suite.getGlobalVariables()) {
                String k = TestSuite.bareVarName(v.getName());
                if (k != null && !k.isBlank()) out.add(collectionVar(k, v.getValue() != null ? v.getValue() : ""));
            }
        }
        return out;
    }

    private Map<String, AuthProfile> profileMap(TestSuite suite) {
        Map<String, AuthProfile> m = new LinkedHashMap<>();
        for (AuthProfile ap : nullSafe(suite.getAuthProfiles())) {
            if (ap.getName() != null) m.put(ap.getName(), ap);
        }
        return m;
    }

    private ArrayNode buildHeaders(TestRequest tc, List<Param> envHeaders) {
        ArrayNode headers = mapper.createArrayNode();
        Set<String> seen = new HashSet<>();

        String rawHeaders = tc.getHeaders();
        if (rawHeaders != null && !rawHeaders.isBlank()) {
            for (String line : rawHeaders.split("\\n")) {
                int colon = line.indexOf(":");
                if (colon <= 0) continue;
                String key = line.substring(0, colon).trim();
                ObjectNode h = mapper.createObjectNode();
                h.put("key",   key);
                h.put("value", line.substring(colon + 1).trim());
                headers.add(h);
                seen.add(key.toLowerCase());
            }
        }

        boolean hasBody = (tc.getFormParams() != null && !tc.getFormParams().isEmpty())
                       || (tc.getJsonBody() != null && !tc.getJsonBody().isBlank());
        for (Param p : nullSafe(envHeaders)) {
            String key = p.getKey();
            if (key == null || key.isBlank()) continue;
            if (!seen.add(key.toLowerCase())) continue;                       // request override wins
            if ("authorization".equalsIgnoreCase(key)) continue;              // collection auth handles it
            if ("content-type".equalsIgnoreCase(key) && hasBody) continue;    // body mode drives CT
            ObjectNode h = mapper.createObjectNode();
            h.put("key",   key);
            h.put("value", "{{" + headerVar(key) + "}}");
            headers.add(h);
        }
        return headers;
    }

    private static String headerVar(String key) {
        return "header_" + key.replace("-", "_");
    }

    /** Header keys used by either environment, first-seen order, deduped. */
    private List<Param> unionEnvHeaders(Environment a, Environment b) {
        List<Param> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Environment e : Arrays.asList(a, b)) {
            if (e == null) continue;
            for (Param h : nullSafe(e.getHeaders())) {
                if (h.getKey() == null || h.getKey().isBlank()) continue;
                if (seen.add(h.getKey().toLowerCase())) out.add(h);
            }
        }
        return out;
    }

    private ObjectNode buildBody(TestRequest tc) {
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
