package com.fpt.comparison_tool.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fpt.comparison_tool.model.*;

import java.time.Instant;
import java.util.*;

/**
 * Converts a TestSuite into a Bruno-compatible bundle:
 *   - 1 collection YAML (OpenAPI 3.0 — Bruno reads OpenAPI as native input)
 *   - environment JSON files (one per env) listing baseUrl + authToken vars
 *
 * Public API mirrors PostmanExporter for symmetry:
 *
 *   exportSingle(suite, isSource)
 *     → YAML where each request's server is hardcoded to that env's URL.
 *       Auth is declared per-request as bearer; user pastes token in env var.
 *
 *   exportBoth(suite)
 *     → BrunoExport(collectionYaml, sourceEnvJson, targetEnvJson)
 *       Collection still uses the env's URL hardcoded in `servers:` per path
 *       (OpenAPI doesn't have a clean "switch base URL per env" knob).
 *       Both env files carry the same {{authToken}} variable; user picks env in
 *       Bruno UI and pastes the appropriate token.
 *
 * Auth mapping:
 *   NONE                → no security
 *   BEARER, OAUTH2_CC   → http/bearer scheme; token goes in env var (literal {})
 *   BASIC               → http/basic scheme; user provides creds in Bruno UI
 *   SAML                → unsupported, falls back to no security with a note
 *
 * Variable placeholders {{var}} stay literal in endpoints / bodies — Bruno uses
 * the same {{var}} syntax so no transformation is needed.
 */
public class BrunoExporter {

    public record BrunoExport(byte[] collectionYaml, byte[] sourceEnvJson, byte[] targetEnvJson) {}

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper yamlMapper = new ObjectMapper(
            new YAMLFactory()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));

    // ─── Public entry points ──────────────────────────────────────────────────

    public byte[] exportSingle(TestSuite suite, boolean isSource) throws Exception {
        Environment env = resolveEnv(suite, isSource);
        AuthProfile auth = resolveAuth(suite, env);
        return buildCollection(suite, env, auth);
    }

    public BrunoExport exportBoth(TestSuite suite) throws Exception {
        Environment sourceEnv = resolveEnv(suite, true);
        Environment targetEnv = resolveEnv(suite, false);
        AuthProfile sourceAuth = resolveAuth(suite, sourceEnv);
        AuthProfile targetAuth = resolveAuth(suite, targetEnv);

        // The collection YAML hardcodes the target env URL by default (most common
        // debug scenario after a refactor). Source env still gets its own env JSON
        // so users can switch and override baseUrl via variables if needed.
        byte[] collection = buildCollection(suite, targetEnv, targetAuth);
        byte[] sourceJson = buildEnvFile("source", sourceEnv, sourceAuth);
        byte[] targetJson = buildEnvFile("target", targetEnv, targetAuth);

        return new BrunoExport(collection, sourceJson, targetJson);
    }

    // ─── Collection (OpenAPI 3.0 YAML) ────────────────────────────────────────

    private byte[] buildCollection(TestSuite suite, Environment env, AuthProfile auth) throws Exception {
        ObjectNode root = jsonMapper.createObjectNode();
        root.put("openapi", "3.0.0");

        ObjectNode info = root.putObject("info");
        String suiteName = suite.getSettings() != null && suite.getSettings().getSuiteName() != null
                ? suite.getSettings().getSuiteName() : "Test Suite";
        info.put("title", suiteName);
        info.put("version", "1.0.0");
        if (suite.getSettings() != null && suite.getSettings().getDescription() != null) {
            info.put("description", suite.getSettings().getDescription());
        }

        String baseUrl = env != null && env.getUrl() != null ? env.getUrl() : "";
        AuthType authType = auth != null ? auth.getType() : AuthType.NONE;

        ObjectNode paths = root.putObject("paths");
        ObjectNode schemas = jsonMapper.createObjectNode();
        ObjectNode requestBodies = jsonMapper.createObjectNode();
        ObjectNode securitySchemes = jsonMapper.createObjectNode();

        Set<String> usedOpIds = new HashSet<>();

        for (TestGroup group : nullSafe(suite.getTestGroups())) {
            for (TestCase tc : nullSafe(group.getTestCases())) {
                if (!tc.isEnabled()) continue;
                addPath(paths, schemas, requestBodies, securitySchemes,
                        tc, group, baseUrl, authType, usedOpIds);
            }
        }

        ObjectNode components = root.putObject("components");
        if (schemas.size() > 0)        components.set("schemas", schemas);
        if (requestBodies.size() > 0)  components.set("requestBodies", requestBodies);
        if (securitySchemes.size() > 0) components.set("securitySchemes", securitySchemes);
        if (components.size() == 0)    root.remove("components");

        return yamlMapper.writeValueAsBytes(root);
    }

    private void addPath(ObjectNode paths, ObjectNode schemas, ObjectNode requestBodies,
                         ObjectNode securitySchemes, TestCase tc, TestGroup group,
                         String baseUrl, AuthType authType, Set<String> usedOpIds) {
        String endpoint = tc.getEndpoint() != null ? tc.getEndpoint() : "/";
        if (!endpoint.startsWith("/")) endpoint = "/" + endpoint;

        String method = tc.getMethod() != null ? tc.getMethod().name().toLowerCase() : "get";
        String opId = uniqueOpId(tc, usedOpIds);

        // Get or create path entry (Bruno keeps separate ops per HTTP method on same path)
        ObjectNode pathNode = paths.has(endpoint) ? (ObjectNode) paths.get(endpoint) : paths.putObject(endpoint);
        ObjectNode operation = pathNode.putObject(method);

        operation.put("summary", tc.getName() != null ? tc.getName() : tc.getId());
        operation.put("operationId", opId);
        operation.put("description", tc.getDescription() != null ? tc.getDescription() : "");

        operation.putArray("tags").add(group.getName());

        operation.putObject("responses").putObject("200").put("description", "");

        // Parameters: query params + custom headers
        ArrayNode params = operation.putArray("parameters");
        if (tc.getQueryParams() != null) {
            for (Param p : tc.getQueryParams()) {
                ObjectNode param = params.addObject();
                param.put("name", p.getKey());
                param.put("in", "query");
                param.put("required", true);
                param.putObject("schema").put("type", "string");
                if (p.getValue() != null) param.put("example", p.getValue());
            }
        }
        // TC-level header overrides
        if (tc.getHeaders() != null && !tc.getHeaders().isBlank()) {
            for (String line : tc.getHeaders().split("\\r?\\n")) {
                int idx = line.indexOf(':');
                if (idx <= 0) continue;
                String name = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim();
                if (name.isEmpty() || name.equalsIgnoreCase("Authorization")) continue;
                ObjectNode param = params.addObject();
                param.put("name", name);
                param.put("in", "header");
                param.put("required", true);
                param.putObject("schema").put("type", "string");
                param.put("example", value);
            }
        }
        if (params.size() == 0) operation.remove("parameters");

        // Request body
        boolean hasJson = tc.getJsonBody() != null && !tc.getJsonBody().isBlank();
        boolean hasForm = tc.getFormParams() != null && !tc.getFormParams().isEmpty();
        if (hasJson) {
            addJsonBody(operation, schemas, requestBodies, tc, opId);
        } else if (hasForm) {
            addFormBody(operation, tc);
        }

        // Security
        if (authType != AuthType.NONE && authType != AuthType.SAML) {
            String schemeName = brunoSchemeName(authType);
            securitySchemes.set(schemeName, buildSecurityScheme(authType));
            operation.putArray("security").addObject().putArray(schemeName);
        }

        // Server (per-operation) — Bruno honors this when each op points to a different host
        operation.putArray("servers").addObject().put("url", baseUrl);
    }

    private void addJsonBody(ObjectNode operation, ObjectNode schemas, ObjectNode requestBodies,
                             TestCase tc, String opId) {
        String schemaName = opId;
        // Schema with example
        ObjectNode schema = schemas.putObject(schemaName);
        schema.put("type", "object");
        try {
            schema.set("example", jsonMapper.readTree(tc.getJsonBody()));
        } catch (Exception e) {
            schema.put("example", tc.getJsonBody());
        }

        // requestBodies entry referencing the schema
        ObjectNode rb = requestBodies.putObject(schemaName);
        ObjectNode content = rb.putObject("content").putObject("application/json");
        content.putObject("schema").put("$ref", "#/components/schemas/" + schemaName);
        rb.put("description", "");
        rb.put("required", true);

        operation.putObject("requestBody").put("$ref", "#/components/requestBodies/" + schemaName);
    }

    private void addFormBody(ObjectNode operation, TestCase tc) {
        ObjectNode rb = operation.putObject("requestBody");
        ObjectNode schema = rb.putObject("content")
                .putObject("application/x-www-form-urlencoded")
                .putObject("schema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode example = jsonMapper.createObjectNode();
        for (Param p : tc.getFormParams()) {
            props.putObject(p.getKey()).put("type", "string");
            example.put(p.getKey(), p.getValue());
        }
        schema.set("example", example);
        rb.put("required", true);
    }

    private ObjectNode buildSecurityScheme(AuthType authType) {
        ObjectNode scheme = jsonMapper.createObjectNode();
        switch (authType) {
            case BEARER, CLIENT_CREDENTIALS -> {
                scheme.put("type", "http");
                scheme.put("scheme", "bearer");
            }
            case BASIC -> {
                scheme.put("type", "http");
                scheme.put("scheme", "basic");
            }
            default -> {
                scheme.put("type", "http");
                scheme.put("scheme", "bearer");
            }
        }
        return scheme;
    }

    private String brunoSchemeName(AuthType authType) {
        return switch (authType) {
            case BEARER, CLIENT_CREDENTIALS -> "bearerAuth";
            case BASIC                       -> "basicAuth";
            default                          -> "auth";
        };
    }

    // ─── Environment JSON ─────────────────────────────────────────────────────

    private byte[] buildEnvFile(String label, Environment env, AuthProfile auth) throws Exception {
        ObjectNode root = jsonMapper.createObjectNode();
        root.put("name", label + " env");

        ArrayNode vars = root.putArray("variables");
        vars.add(variable("baseUrl", env != null ? env.getUrl() : "", false));

        if (auth != null) {
            switch (auth.getType()) {
                case BEARER -> vars.add(variable("authToken", auth.getToken() != null ? auth.getToken() : "", true));
                case CLIENT_CREDENTIALS -> {
                    // No pre-request flow generated; user pastes a fetched token here
                    vars.add(variable("authToken", "", true));
                    vars.add(variable("clientId", auth.getClientId() != null ? auth.getClientId() : "", false));
                    vars.add(variable("clientSecret", auth.getClientSecret() != null ? auth.getClientSecret() : "", true));
                    vars.add(variable("tokenUrl", auth.getTokenUrl() != null ? auth.getTokenUrl() : "", false));
                }
                case BASIC -> {
                    vars.add(variable("username", auth.getUsername() != null ? auth.getUsername() : "", false));
                    vars.add(variable("password", auth.getPassword() != null ? auth.getPassword() : "", true));
                }
                default -> { /* NONE / SAML — no auth vars */ }
            }
        }

        ObjectNode info = root.putObject("info");
        info.put("type", "bruno-environment");
        info.put("exportedAt", Instant.now().toString());
        info.put("exportedUsing", "API-Comparison-Tool");

        return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
    }

    private ObjectNode variable(String name, String value, boolean secret) {
        ObjectNode v = jsonMapper.createObjectNode();
        v.put("name", name);
        v.put("value", value);
        v.put("type", "text");
        v.put("enabled", true);
        v.put("secret", secret);
        return v;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Environment resolveEnv(TestSuite suite, boolean isSource) {
        if (suite.getSettings() == null || suite.getSettings().getExecutionConfig() == null) return null;
        String name = isSource
                ? suite.getSettings().getExecutionConfig().getSourceEnvironment()
                : suite.getSettings().getExecutionConfig().getTargetEnvironment();
        return suite.findEnvironment(name);
    }

    private AuthProfile resolveAuth(TestSuite suite, Environment env) {
        if (env == null || env.getAuthProfile() == null || suite.getAuthProfiles() == null) return null;
        return suite.getAuthProfiles().stream()
                .filter(p -> env.getAuthProfile().equals(p.getName()))
                .findFirst().orElse(null);
    }

    private String uniqueOpId(TestCase tc, Set<String> used) {
        String base = (tc.getId() != null ? tc.getId() : "op")
                .toLowerCase().replaceAll("[^a-z0-9_]", "_");
        String candidate = base;
        int i = 1;
        while (used.contains(candidate)) {
            candidate = base + "_" + (++i);
        }
        used.add(candidate);
        return candidate;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list != null ? list : Collections.emptyList();
    }
}
