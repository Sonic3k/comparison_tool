package com.fpt.comparison_tool.generator;

import com.fpt.comparison_tool.model.*;

import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates JMeter 5.x test plans (.jmx) from a TestSuite.
 *
 * Structure:
 *   TestPlan
 *     User Defined Variables  — baseUrl + auth vars
 *     [SetupThreadGroup]      — fetches OAuth2 token via JSR223 Groovy sampler (CLIENT_CREDENTIALS only)
 *     [ThreadGroup per group] — HTTP Request samplers ordered by Phase (SETUP→TEST→TEARDOWN)
 *       HeaderManager         — Authorization header (Bearer/Basic)
 *       ConfigTestElement     — HTTP Request Defaults (base URL)
 *       HTTPSamplerProxy      — one per test case
 *         [HeaderManager]     — per-TC custom headers if any
 *
 * Auth:
 *   NONE               → no Authorization header
 *   BEARER             → Authorization: Bearer ${authToken}, UDV sets actual token
 *   BASIC              → Authorization: Basic ${authBase64}, UDV sets base64(user:pass)
 *   CLIENT_CREDENTIALS → SetupThreadGroup with JSR223 Groovy fetches token into props/vars
 */
public class JmeterExporter {

    public record JmxExport(byte[] sourceJmx, byte[] targetJmx) {}

    // ─── Public entry points ──────────────────────────────────────────────────

    public byte[] exportSingle(TestSuite suite, boolean isSource) throws Exception {
        Environment env  = resolveEnv(suite, isSource);
        AuthProfile auth = resolveAuth(suite, env);
        return buildPlan(suite, env, auth).getBytes("UTF-8");
    }

    public JmxExport exportBoth(TestSuite suite) throws Exception {
        Environment srcEnv  = resolveEnv(suite, true);
        Environment tgtEnv  = resolveEnv(suite, false);
        AuthProfile srcAuth = resolveAuth(suite, srcEnv);
        AuthProfile tgtAuth = resolveAuth(suite, tgtEnv);
        return new JmxExport(
            buildPlan(suite, srcEnv, srcAuth).getBytes("UTF-8"),
            buildPlan(suite, tgtEnv, tgtAuth).getBytes("UTF-8")
        );
    }

    // ─── Plan ─────────────────────────────────────────────────────────────────

    private String buildPlan(TestSuite suite, Environment env, AuthProfile auth) {
        String name  = suiteName(suite);
        ParsedUrl pu = parseUrl(env != null ? env.getUrl() : "");

        Xml x = new Xml();
        x.line("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        x.open("jmeterTestPlan", "version", "1.2", "properties", "5.0", "jmeter", "5.6.3");
        x.open("hashTree");

        // TestPlan
        x.open("TestPlan", "guiclass", "TestPlanGui", "testclass", "TestPlan", "testname", name, "enabled", "true");
        x.prop("stringProp", "TestPlan.comments", "");
        x.prop("boolProp",   "TestPlan.functional_mode", "false");
        x.prop("boolProp",   "TestPlan.tearDown_on_shutdown", "true");
        x.prop("boolProp",   "TestPlan.serialize_threadgroups", "false");
        x.open("elementProp", "name", "TestPlan.user_defined_variables",
               "elementType", "Arguments", "guiclass", "ArgumentsPanel",
               "testclass", "Arguments", "testname", "User Defined Variables", "enabled", "true");
        x.open("collectionProp", "name", "Arguments.arguments");
        buildUDVs(x, pu, auth);
        x.close("collectionProp");
        x.close("elementProp");
        x.close("TestPlan");

        x.open("hashTree");

        if (isOauth2(auth)) buildOauth2SetupGroup(x);

        for (TestGroup g : nullSafe(suite.getTestGroups())) {
            buildThreadGroup(x, g, pu, auth);
        }

        x.close("hashTree");
        x.close("hashTree");
        x.close("jmeterTestPlan");
        return x.toString();
    }

    // ─── User Defined Variables ───────────────────────────────────────────────

    private void buildUDVs(Xml x, ParsedUrl pu, AuthProfile auth) {
        udv(x, "baseUrl", pu.baseUrl);
        if (auth == null || auth.getType() == null || auth.getType() == AuthType.NONE) return;
        switch (auth.getType()) {
            case BEARER ->
                udv(x, "authToken", notBlank(auth.getToken(), "your-bearer-token"));
            case BASIC -> {
                String u  = notBlank(auth.getUsername(), "");
                String p  = notBlank(auth.getPassword(), "");
                String b64 = Base64.getEncoder().encodeToString((u + ":" + p).getBytes());
                udv(x, "authUsername", u);
                udv(x, "authPassword", p);
                udv(x, "authBase64",   b64);
            }
            case CLIENT_CREDENTIALS -> {
                udv(x, "tokenUrl",     notBlank(auth.getTokenUrl(), ""));
                udv(x, "clientId",     notBlank(auth.getClientId(), ""));
                udv(x, "clientSecret", notBlank(auth.getClientSecret(), ""));
                udv(x, "scope",        notBlank(auth.getScope(), ""));
                udv(x, "authToken",    ""); // populated at runtime
            }
            default -> {}
        }
    }

    private void udv(Xml x, String name, String value) {
        x.open("elementProp", "name", name, "elementType", "Argument");
        x.prop("stringProp", "Argument.name",     name);
        x.prop("stringProp", "Argument.value",    value);
        x.prop("stringProp", "Argument.desc",     "");
        x.prop("stringProp", "Argument.metadata", "=");
        x.close("elementProp");
    }

    // ─── OAuth2 SetupThreadGroup ──────────────────────────────────────────────

    private void buildOauth2SetupGroup(Xml x) {
        x.open("SetupThreadGroup", "guiclass", "SetupThreadGroupGui",
               "testclass", "SetupThreadGroup", "testname", "Auth Setup (OAuth2 Token)", "enabled", "true");
        x.prop("stringProp", "ThreadGroup.on_sample_error", "continue");
        x.open("elementProp", "name", "ThreadGroup.main_controller",
               "elementType", "LoopController", "guiclass", "LoopControlPanel",
               "testclass", "LoopController", "testname", "Loop Controller", "enabled", "true");
        x.prop("boolProp",   "LoopController.continue_forever", "false");
        x.prop("stringProp", "LoopController.loops", "1");
        x.close("elementProp");
        x.prop("stringProp", "ThreadGroup.num_threads", "1");
        x.prop("stringProp", "ThreadGroup.ramp_time",   "0");
        x.prop("boolProp",   "ThreadGroup.scheduler",   "false");
        x.close("SetupThreadGroup");
        x.open("hashTree");
        buildOauth2Sampler(x);
        x.close("hashTree");
    }

    private void buildOauth2Sampler(Xml x) {
        String script = String.join("\n",
            "import groovy.json.JsonSlurper",
            "",
            "def tokenUrl     = vars.get('tokenUrl')",
            "def clientId     = vars.get('clientId')",
            "def clientSecret = vars.get('clientSecret')",
            "def scope        = vars.get('scope') ?: ''",
            "",
            "def body = 'grant_type=client_credentials' +",
            "           '&client_id='     + URLEncoder.encode(clientId,     'UTF-8') +",
            "           '&client_secret=' + URLEncoder.encode(clientSecret, 'UTF-8') +",
            "           (scope ? '&scope=' + URLEncoder.encode(scope, 'UTF-8') : '')",
            "",
            "def conn = new URL(tokenUrl).openConnection()",
            "conn.requestMethod = 'POST'",
            "conn.doOutput = true",
            "conn.setRequestProperty('Content-Type', 'application/x-www-form-urlencoded')",
            "conn.outputStream.write(body.getBytes('UTF-8'))",
            "",
            "def resp  = new JsonSlurper().parse(conn.inputStream)",
            "def token = resp.access_token",
            "",
            "// Store in props (cross-thread) and vars (current thread)",
            "props.put('authToken', token)",
            "vars.put('authToken',  token)",
            "log.info('OAuth2 token fetched: ' + token?.take(20) + '...')"
        );

        x.open("JSR223Sampler", "guiclass", "TestBeanGUI",
               "testclass", "JSR223Sampler", "testname", "Fetch OAuth2 Token", "enabled", "true");
        x.prop("stringProp", "scriptLanguage", "groovy");
        x.prop("stringProp", "script",         script);
        x.prop("stringProp", "filename",   "");
        x.prop("stringProp", "parameters", "");
        x.prop("stringProp", "cacheKey",   "true");
        x.close("JSR223Sampler");
        x.open("hashTree");
        x.close("hashTree");
    }

    // ─── Thread Group ─────────────────────────────────────────────────────────

    private void buildThreadGroup(Xml x, TestGroup group, ParsedUrl pu, AuthProfile auth) {
        x.open("ThreadGroup", "guiclass", "ThreadGroupGui", "testclass", "ThreadGroup",
               "testname", group.getName(), "enabled", "true");
        x.prop("stringProp", "ThreadGroup.on_sample_error", "continue");
        x.open("elementProp", "name", "ThreadGroup.main_controller",
               "elementType", "LoopController", "guiclass", "LoopControlPanel",
               "testclass", "LoopController", "testname", "Loop Controller", "enabled", "true");
        x.prop("boolProp",   "LoopController.continue_forever", "false");
        x.prop("stringProp", "LoopController.loops", "1");
        x.close("elementProp");
        x.prop("stringProp", "ThreadGroup.num_threads", "1");
        x.prop("stringProp", "ThreadGroup.ramp_time",   "1");
        x.prop("boolProp",   "ThreadGroup.scheduler",   "false");
        x.close("ThreadGroup");
        x.open("hashTree");

        buildAuthHeaderManager(x, auth);
        buildHttpDefaults(x, pu);

        for (TestCase tc : orderByPhase(nullSafe(group.getTestCases()))) {
            buildHttpSampler(x, tc, pu);
        }

        x.close("hashTree");
    }

    // ─── Auth Header Manager ──────────────────────────────────────────────────

    private void buildAuthHeaderManager(Xml x, AuthProfile auth) {
        if (auth == null || auth.getType() == null || auth.getType() == AuthType.NONE) return;
        String value = switch (auth.getType()) {
            case BEARER, CLIENT_CREDENTIALS -> "Bearer ${authToken}";
            case BASIC                      -> "Basic ${authBase64}";
            default -> null;
        };
        if (value == null) return;

        x.open("HeaderManager", "guiclass", "HeaderPanel", "testclass", "HeaderManager",
               "testname", "Authorization Header", "enabled", "true");
        x.open("collectionProp", "name", "HeaderManager.headers");
        headerEntry(x, "Authorization", value);
        x.close("collectionProp");
        x.close("HeaderManager");
        x.open("hashTree"); x.close("hashTree");
    }

    // ─── HTTP Request Defaults ────────────────────────────────────────────────

    private void buildHttpDefaults(Xml x, ParsedUrl pu) {
        x.open("ConfigTestElement", "guiclass", "HttpDefaultsGui",
               "testclass", "ConfigTestElement", "testname", "HTTP Request Defaults", "enabled", "true");
        x.prop("stringProp", "HTTPSampler.domain",          pu.host);
        x.prop("stringProp", "HTTPSampler.port",            pu.port > 0 ? String.valueOf(pu.port) : "");
        x.prop("stringProp", "HTTPSampler.protocol",        pu.protocol);
        x.prop("stringProp", "HTTPSampler.path",            pu.basePath);
        x.prop("stringProp", "HTTPSampler.contentEncoding", "UTF-8");
        x.close("ConfigTestElement");
        x.open("hashTree"); x.close("hashTree");
    }

    // ─── HTTP Sampler ─────────────────────────────────────────────────────────

    private void buildHttpSampler(Xml x, TestCase tc, ParsedUrl pu) {
        String method  = tc.getMethod() != null ? tc.getMethod().name() : "GET";
        String path    = pu.basePath + notBlank(tc.getEndpoint(), "");
        String qs      = tc.getQueryParamsAsString();
        String fullPath = qs.isBlank() ? path : path + "?" + qs;
        String prefix  = tc.getPhase() != null && tc.getPhase() != Phase.TEST
                         ? "[" + tc.getPhase().getValue().toUpperCase() + "] " : "";
        String name    = prefix + notBlank(tc.getName(), tc.getId());

        x.open("HTTPSamplerProxy", "guiclass", "HttpTestSampleGui",
               "testclass", "HTTPSamplerProxy", "testname", name,
               "enabled", String.valueOf(tc.isEnabled()));
        x.prop("stringProp", "HTTPSampler.method",   method);
        x.prop("stringProp", "HTTPSampler.path",     fullPath);
        x.prop("stringProp", "HTTPSampler.contentEncoding", "UTF-8");
        x.prop("boolProp",   "HTTPSampler.follow_redirects", "true");
        x.prop("boolProp",   "HTTPSampler.auto_redirects",   "false");
        x.prop("boolProp",   "HTTPSampler.use_keepalive",    "true");
        x.prop("boolProp",   "HTTPSampler.DO_MULTIPART_POST", "false");
        buildBody(x, tc);
        x.close("HTTPSamplerProxy");
        x.open("hashTree");
        buildPerRequestHeaders(x, tc);
        x.close("hashTree");
    }

    private void buildBody(Xml x, TestCase tc) {
        List<Param> fp  = nullSafe(tc.getFormParams());
        String      jb  = tc.getJsonBody();

        if (!fp.isEmpty()) {
            x.prop("boolProp", "HTTPSampler.postBodyRaw", "false");
            x.open("elementProp", "name", "HTTPSampler.Arguments", "elementType", "Arguments");
            x.open("collectionProp", "name", "Arguments.arguments");
            for (Param p : fp) {
                x.open("elementProp", "name", p.getKey(), "elementType", "HTTPArgument");
                x.prop("boolProp",   "HTTPArgument.always_encode", "true");
                x.prop("stringProp", "Argument.name",              p.getKey());
                x.prop("stringProp", "Argument.value",             p.getValue());
                x.prop("stringProp", "Argument.metadata",          "=");
                x.close("elementProp");
            }
            x.close("collectionProp");
            x.close("elementProp");
            return;
        }

        if (jb != null && !jb.isBlank()) {
            x.prop("boolProp", "HTTPSampler.postBodyRaw", "true");
            x.open("elementProp", "name", "HTTPSampler.Arguments", "elementType", "Arguments");
            x.open("collectionProp", "name", "Arguments.arguments");
            x.open("elementProp", "name", "", "elementType", "HTTPArgument");
            x.prop("boolProp",   "HTTPArgument.always_encode", "false");
            x.prop("stringProp", "Argument.value",             jb);
            x.prop("stringProp", "Argument.metadata",          "=");
            x.close("elementProp");
            x.close("collectionProp");
            x.close("elementProp");
            return;
        }

        x.prop("boolProp", "HTTPSampler.postBodyRaw", "false");
        x.open("elementProp", "name", "HTTPSampler.Arguments", "elementType", "Arguments");
        x.open("collectionProp", "name", "Arguments.arguments");
        x.close("collectionProp");
        x.close("elementProp");
    }

    private void buildPerRequestHeaders(Xml x, TestCase tc) {
        if (tc.getHeaders() == null || tc.getHeaders().isBlank()) return;
        List<String[]> parsed = new ArrayList<>();
        for (String line : tc.getHeaders().split("\\n")) {
            int c = line.indexOf(":");
            if (c > 0) parsed.add(new String[]{ line.substring(0, c).trim(), line.substring(c + 1).trim() });
        }
        if (parsed.isEmpty()) return;
        x.open("HeaderManager", "guiclass", "HeaderPanel",
               "testclass", "HeaderManager", "testname", "Request Headers", "enabled", "true");
        x.open("collectionProp", "name", "HeaderManager.headers");
        for (String[] h : parsed) headerEntry(x, h[0], h[1]);
        x.close("collectionProp");
        x.close("HeaderManager");
        x.open("hashTree"); x.close("hashTree");
    }

    private void headerEntry(Xml x, String name, String value) {
        x.open("elementProp", "name", "", "elementType", "Header");
        x.prop("stringProp", "Header.name",  name);
        x.prop("stringProp", "Header.value", value);
        x.close("elementProp");
    }

    // ─── Lookup / util ────────────────────────────────────────────────────────

    private Environment resolveEnv(TestSuite suite, boolean isSource) {
        List<Environment> envs = nullSafe(suite.getEnvironments());
        SuiteSettings     s    = suite.getSettings();
        if (s == null || s.getExecutionConfig() == null) return null;
        String name = isSource ? s.getExecutionConfig().getSourceEnvironment()
                               : s.getExecutionConfig().getTargetEnvironment();
        if (name == null) return null;
        return envs.stream().filter(e -> name.equals(e.getName())).findFirst().orElse(null);
    }

    private AuthProfile resolveAuth(TestSuite suite, Environment env) {
        if (env == null || env.getAuthProfile() == null) return null;
        return nullSafe(suite.getAuthProfiles()).stream()
                .filter(p -> env.getAuthProfile().equals(p.getName()))
                .findFirst().orElse(null);
    }

    private List<TestCase> orderByPhase(List<TestCase> tcs) {
        List<TestCase> result = new ArrayList<>();
        result.addAll(tcs.stream().filter(tc -> tc.getPhase() == Phase.SETUP).collect(Collectors.toList()));
        result.addAll(tcs.stream().filter(tc -> tc.getPhase() == null || tc.getPhase() == Phase.TEST).collect(Collectors.toList()));
        result.addAll(tcs.stream().filter(tc -> tc.getPhase() == Phase.TEARDOWN).collect(Collectors.toList()));
        return result;
    }

    private ParsedUrl parseUrl(String raw) {
        if (raw == null || raw.isBlank()) return new ParsedUrl("", "https", "", -1, "");
        try {
            URL u = new URL(raw);
            return new ParsedUrl(raw, u.getProtocol(), u.getHost(), u.getPort(),
                                 u.getPath() != null ? u.getPath() : "");
        } catch (Exception e) {
            return new ParsedUrl(raw, "https", raw, -1, "");
        }
    }

    private record ParsedUrl(String baseUrl, String protocol, String host, int port, String basePath) {}

    private String suiteName(TestSuite s) {
        String n = s.getSettings() != null ? s.getSettings().getSuiteName() : null;
        return (n != null && !n.isBlank()) ? n : "API Suite";
    }

    private boolean isOauth2(AuthProfile a) { return a != null && a.getType() == AuthType.CLIENT_CREDENTIALS; }
    private <T> List<T> nullSafe(List<T> l)  { return l != null ? l : Collections.emptyList(); }
    private String notBlank(String v, String fb) { return v != null && !v.isBlank() ? v : fb; }

    // ─── XML builder with explicit tag stack ──────────────────────────────────

    private static class Xml {
        private final StringBuilder   sb    = new StringBuilder();
        private final Deque<String>   stack = new ArrayDeque<>();
        private int                   depth = 0;

        void line(String s) { sb.append(s).append("\n"); }

        /** Open a tag: open("foo", "k1","v1", "k2","v2") */
        void open(String tag, String... kvPairs) {
            indent();
            sb.append("<").append(tag);
            for (int i = 0; i + 1 < kvPairs.length; i += 2) {
                sb.append(" ").append(kvPairs[i]).append("=\"").append(esc(kvPairs[i + 1])).append("\"");
            }
            sb.append(">\n");
            stack.push(tag);
            depth++;
        }

        /** Close the most-recently opened tag */
        void close(String tag) {
            depth--;
            indent();
            sb.append("</").append(tag).append(">\n");
            if (!stack.isEmpty() && stack.peek().equals(tag)) stack.pop();
        }

        /** Inline property: <stringProp name="x">value</stringProp> */
        void prop(String element, String name, String value) {
            indent();
            sb.append("<").append(element).append(" name=\"").append(esc(name)).append("\">")
              .append(esc(value))
              .append("</").append(element).append(">\n");
        }

        private void indent() { for (int i = 0; i < depth; i++) sb.append("  "); }

        private static String esc(String s) {
            if (s == null) return "";
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;");
        }

        @Override public String toString() { return sb.toString(); }
    }
}
