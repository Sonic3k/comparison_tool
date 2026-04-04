package com.fpt.comparison_tool.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.comparison_tool.dto.ExecutionProgress;
import com.fpt.comparison_tool.model.*;
import com.fpt.comparison_tool.service.AssertionService.AssertionLine;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@Service
public class ExecutionService {

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String GLOBAL_SETUP_PREFIX    = "Global Setup";
    private static final String GLOBAL_TEARDOWN_PREFIX = "Global Teardown";

    private final RestTemplate restTemplate;
    private final AuthService authService;
    private final ComparisonService comparisonService;
    private final AssertionService assertionService;
    private final ExecutorService executor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ExecutionService(RestTemplate restTemplate,
                            AuthService authService,
                            ComparisonService comparisonService,
                            AssertionService assertionService,
                            ExecutorService executionExecutor) {
        this.restTemplate      = restTemplate;
        this.authService       = authService;
        this.comparisonService = comparisonService;
        this.assertionService  = assertionService;
        this.executor          = executionExecutor;
    }

    public void startAsync(TestSuite suite, List<String> groupFilter, ExecutionProgress progress) {
        List<TestGroup> allGroups = filterGroups(suite, groupFilter);
        ExecutionConfig ec0 = suite.getSettings().getExecutionConfig();
        VerificationMode suiteFilter = ec0.getVerificationMode();

        // Count only TCs that will actually run
        int total = allGroups.stream()
                .mapToInt(g -> (int) g.getTestCases().stream()
                        .filter(TestCase::isEnabled)
                        .filter(tc -> !shouldSkip(tc, suiteFilter))
                        .count())
                .sum();
        progress.start(total);

        CompletableFuture.runAsync(() -> {
            try {
                ExecutionConfig ec = suite.getSettings().getExecutionConfig();
                ExecutionMode mode = ec.getMode();
                VerificationMode filter = ec.getVerificationMode();

                Environment sourceEnv = suite.findEnvironment(ec.getSourceEnvironment());
                Environment targetEnv = suite.findEnvironment(ec.getTargetEnvironment());

                // Suite-scope variables (populated by Global Setup groups)
                Map<String, String> suiteVars = new ConcurrentHashMap<>();

                // Separate groups: global setup → normal → global teardown
                List<TestGroup> setupGroups   = new ArrayList<>();
                List<TestGroup> normalGroups  = new ArrayList<>();
                List<TestGroup> teardownGroups = new ArrayList<>();

                for (TestGroup g : allGroups) {
                    if (g.getName().startsWith(GLOBAL_SETUP_PREFIX))         setupGroups.add(g);
                    else if (g.getName().startsWith(GLOBAL_TEARDOWN_PREFIX)) teardownGroups.add(g);
                    else                                                     normalGroups.add(g);
                }

                // 1. Global Setup groups — sequential, variables go to suiteVars
                for (TestGroup g : setupGroups) {
                    executeGroup(g, suite, mode, filter, sourceEnv, targetEnv, progress, suiteVars);
                }

                // 2. Normal groups — each gets its own groupVars (initialized with suiteVars)
                for (TestGroup g : normalGroups) {
                    Map<String, String> groupVars = new ConcurrentHashMap<>(suiteVars);
                    executeGroup(g, suite, mode, filter, sourceEnv, targetEnv, progress, groupVars);
                }

                // 3. Global Teardown groups — sequential, always run
                for (TestGroup g : teardownGroups) {
                    executeGroup(g, suite, mode, filter, sourceEnv, targetEnv, progress, suiteVars);
                }

            } catch (Exception e) {
                progress.abort("Unexpected error: " + e.getMessage());
            } finally {
                if (progress.isRunning()) progress.finish();
            }
        }, executor);
    }

    // ── Group execution with phases ───────────────────────────────────────────

    private void executeGroup(TestGroup group, TestSuite suite, ExecutionMode execMode,
                              VerificationMode suiteFilter,
                              Environment sourceEnv, Environment targetEnv,
                              ExecutionProgress progress,
                              Map<String, String> variables) {

        List<TestCase> enabled = group.getTestCases().stream()
                .filter(TestCase::isEnabled)
                .filter(tc -> !shouldSkip(tc, suiteFilter))
                .collect(Collectors.toList());

        // Split by phase
        List<TestCase> setupTCs    = enabled.stream().filter(tc -> tc.getPhase() == Phase.SETUP).collect(Collectors.toList());
        List<TestCase> testTCs     = enabled.stream().filter(tc -> tc.getPhase() == Phase.TEST).collect(Collectors.toList());
        List<TestCase> teardownTCs = enabled.stream().filter(tc -> tc.getPhase() == Phase.TEARDOWN).collect(Collectors.toList());

        // 1. Setup — always sequential
        for (TestCase tc : setupTCs) {
            executeAndRecord(tc, group, suite, sourceEnv, targetEnv, progress, variables);
        }

        // 2. Test — parallel or sequential per config
        if (execMode == ExecutionMode.PARALLEL && testTCs.size() > 1) {
            List<CompletableFuture<Void>> futures = testTCs.stream()
                    .map(tc -> CompletableFuture.runAsync(
                            () -> executeAndRecord(tc, group, suite, sourceEnv, targetEnv, progress, variables),
                            executor))
                    .collect(Collectors.toList());
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } else {
            for (TestCase tc : testTCs) {
                executeAndRecord(tc, group, suite, sourceEnv, targetEnv, progress, variables);
            }
        }

        // 3. Teardown — always sequential, always runs
        for (TestCase tc : teardownTCs) {
            executeAndRecord(tc, group, suite, sourceEnv, targetEnv, progress, variables);
        }
    }

    /**
     * Should this TC be skipped under the given suite Verification Mode filter?
     *
     * null filter = run all.
     * Setup/teardown phase TCs always run regardless of filter.
     */
    private boolean shouldSkip(TestCase tc, VerificationMode suiteFilter) {
        if (suiteFilter == null) return false;
        // Setup/teardown always run
        if (tc.getPhase() == Phase.SETUP || tc.getPhase() == Phase.TEARDOWN) return false;

        VerificationMode tcMode = tc.getVerificationMode() != null
                ? tc.getVerificationMode() : VerificationMode.COMPARISON;
        return switch (suiteFilter) {
            case COMPARISON -> tcMode == VerificationMode.AUTOMATION || tcMode == VerificationMode.NONE;
            case AUTOMATION -> tcMode == VerificationMode.COMPARISON || tcMode == VerificationMode.NONE;
            case BOTH       -> tcMode != VerificationMode.BOTH;
            case NONE       -> tcMode != VerificationMode.NONE;
        };
    }

    // ── Single TC ─────────────────────────────────────────────────────────────

    private void executeAndRecord(TestCase tc, TestGroup group, TestSuite suite,
                                  Environment sourceEnv, Environment targetEnv,
                                  ExecutionProgress progress,
                                  Map<String, String> variables) {
        VerificationMode verificationMode = tc.getVerificationMode() != null
                ? tc.getVerificationMode() : VerificationMode.COMPARISON;
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);

        // Resolve {{variables}} in TC fields before execution
        TestCase resolved = resolveVariables(tc, variables);

        try {
            int delayMs = suite.getSettings().getExecutionConfig().getDelayBetweenRequests();
            AuthProfile targetAuth = findProfile(suite, targetEnv != null ? targetEnv.getAuthProfile() : null);

            String responseBody = switch (verificationMode) {
                case COMPARISON -> {
                    runComparison(resolved, tc, group, suite, sourceEnv, targetEnv, progress, delayMs, timestamp);
                    yield tc.getResult() != null ? tc.getResult().getTargetResponse() : null;
                }
                case AUTOMATION -> runAutomation(resolved, tc, group, suite, targetEnv, targetAuth, progress, timestamp);
                case BOTH       -> runBoth(resolved, tc, group, suite, sourceEnv, targetEnv, progress, delayMs, timestamp);
                case NONE       -> runNone(resolved, tc, group, suite, targetEnv, targetAuth, progress, timestamp);
            };

            // Extract variables from response if configured
            if (responseBody != null && tc.getExtractVariables() != null && !tc.getExtractVariables().isBlank()) {
                extractVariables(responseBody, tc.getExtractVariables(), variables);
            }

        } catch (Exception e) {
            TestResult r = new TestResult();
            r.setStatus(ExecutionStatus.ERROR);
            r.setModeRun(verificationMode.getValue());
            r.setComparisonResult("Execution error: " + e.getMessage());
            r.setExecutedAt(timestamp);
            tc.setResult(r);
            progress.recordError(group.getName(), tc.getId());
        }
    }

    // ── NONE mode ─────────────────────────────────────────────────────────────

    private String runNone(TestCase resolved, TestCase original, TestGroup group, TestSuite suite,
                           Environment targetEnv, AuthProfile targetAuth,
                           ExecutionProgress progress, String timestamp) throws Exception {
        ResponseEntity<String> targetResp = callEndpoint(targetEnv, resolved, targetAuth);

        int tgtStatus = targetResp.getStatusCode().value();
        String tgtBody = targetResp.getBody();

        TestResult r = new TestResult();
        r.setModeRun(VerificationMode.NONE.getValue());
        r.setTargetStatus(String.valueOf(tgtStatus));
        r.setTargetResponse(tgtBody);
        r.setExecutedAt(timestamp);
        r.setStatus(tgtStatus < 400 ? ExecutionStatus.PASSED : ExecutionStatus.ERROR);
        r.setComparisonResult(tgtStatus >= 400 ? "Target returned " + tgtStatus : "");
        original.setResult(r);

        if (r.getStatus() == ExecutionStatus.PASSED) progress.recordPassed(group.getName(), original.getId());
        else                                          progress.recordError(group.getName(), original.getId());

        return tgtBody;
    }

    // ── COMPARISON mode ───────────────────────────────────────────────────────

    private void runComparison(TestCase resolved, TestCase original, TestGroup group, TestSuite suite,
                               Environment sourceEnv, Environment targetEnv,
                               ExecutionProgress progress, int delayMs, String timestamp) throws Exception {
        ComparisonConfig cmp = resolveComparison(original, suite);

        AuthProfile sourceAuth = findProfile(suite, sourceEnv != null ? sourceEnv.getAuthProfile() : null);
        AuthProfile targetAuth = findProfile(suite, targetEnv != null ? targetEnv.getAuthProfile() : null);

        ResponseEntity<String> sourceResp = callEndpoint(sourceEnv, resolved, sourceAuth);
        if (delayMs > 0) Thread.sleep(delayMs);
        ResponseEntity<String> targetResp = callEndpoint(targetEnv, resolved, targetAuth);

        int srcStatus = sourceResp.getStatusCode().value();
        int tgtStatus = targetResp.getStatusCode().value();
        String srcBody = sourceResp.getBody();
        String tgtBody = targetResp.getBody();

        TestResult r = new TestResult();
        r.setModeRun(VerificationMode.COMPARISON.getValue());
        r.setSourceStatus(String.valueOf(srcStatus));
        r.setTargetStatus(String.valueOf(tgtStatus));
        r.setSourceResponse(srcBody);
        r.setTargetResponse(tgtBody);
        r.setExecutedAt(timestamp);

        if (!cmp.isCompareErrorResponses() && (srcStatus >= 500 || tgtStatus >= 500)) {
            String msg = srcStatus >= 500
                    ? "Source returned " + srcStatus + " — server error, cannot compare"
                    : "Target returned " + tgtStatus + " — server error, cannot compare";
            r.setStatus(ExecutionStatus.ERROR);
            r.setComparisonResult(msg);
            original.setResult(r);
            progress.recordError(group.getName(), original.getId());
            return;
        }

        List<String> diffs = comparisonService.compare(srcBody, tgtBody, srcStatus, tgtStatus, cmp);
        r.setStatus(diffs.isEmpty() ? ExecutionStatus.PASSED : ExecutionStatus.FAILED);
        r.setComparisonResult(String.join("\n", diffs));
        original.setResult(r);

        if (r.getStatus() == ExecutionStatus.PASSED) progress.recordPassed(group.getName(), original.getId());
        else                                          progress.recordFailed(group.getName(), original.getId());
    }

    // ── AUTOMATION mode ───────────────────────────────────────────────────────

    private String runAutomation(TestCase resolved, TestCase original, TestGroup group, TestSuite suite,
                                 Environment targetEnv, AuthProfile targetAuth,
                                 ExecutionProgress progress, String timestamp) throws Exception {
        AutomationConfig auto = original.getAutomationConfig();

        long start = System.currentTimeMillis();
        ResponseEntity<String> targetResp = callEndpoint(targetEnv, resolved, targetAuth);
        long elapsed = System.currentTimeMillis() - start;

        int tgtStatus = targetResp.getStatusCode().value();
        String tgtBody = targetResp.getBody();

        TestResult r = new TestResult();
        r.setModeRun(VerificationMode.AUTOMATION.getValue());
        r.setTargetStatus(String.valueOf(tgtStatus));
        r.setTargetResponse(tgtBody);
        r.setExecutedAt(timestamp);

        List<AssertionLine> assertions = Collections.emptyList();
        if (auto != null) {
            assertions = assertionService.evaluate(
                    auto.getExpectedStatus(), auto.getExpectedBody(),
                    auto.getExpectedHeaders(), tgtStatus, elapsed, tgtBody,
                    targetResp.getHeaders());
        }

        boolean passed = assertionService.allPassed(assertions);
        r.setStatus(assertions.isEmpty() ? ExecutionStatus.ERROR :
                    passed ? ExecutionStatus.PASSED : ExecutionStatus.FAILED);
        r.setAssertionResult(assertionService.summarize(assertions));
        original.setResult(r);

        if (passed) progress.recordPassed(group.getName(), original.getId());
        else        progress.recordFailed(group.getName(), original.getId());

        return tgtBody;
    }

    // ── BOTH mode ─────────────────────────────────────────────────────────────

    private String runBoth(TestCase resolved, TestCase original, TestGroup group, TestSuite suite,
                           Environment sourceEnv, Environment targetEnv,
                           ExecutionProgress progress, int delayMs, String timestamp) throws Exception {
        ComparisonConfig cmp = resolveComparison(original, suite);
        AutomationConfig auto = original.getAutomationConfig();

        AuthProfile sourceAuth = findProfile(suite, sourceEnv != null ? sourceEnv.getAuthProfile() : null);
        AuthProfile targetAuth = findProfile(suite, targetEnv != null ? targetEnv.getAuthProfile() : null);

        ResponseEntity<String> sourceResp = callEndpoint(sourceEnv, resolved, sourceAuth);
        if (delayMs > 0) Thread.sleep(delayMs);
        long start = System.currentTimeMillis();
        ResponseEntity<String> targetResp = callEndpoint(targetEnv, resolved, targetAuth);
        long elapsed = System.currentTimeMillis() - start;

        int srcStatus = sourceResp.getStatusCode().value();
        int tgtStatus = targetResp.getStatusCode().value();
        String srcBody = sourceResp.getBody();
        String tgtBody = targetResp.getBody();

        TestResult r = new TestResult();
        r.setModeRun(VerificationMode.BOTH.getValue());
        r.setSourceStatus(String.valueOf(srcStatus));
        r.setTargetStatus(String.valueOf(tgtStatus));
        r.setSourceResponse(srcBody);
        r.setTargetResponse(tgtBody);
        r.setExecutedAt(timestamp);

        // Comparison
        String compResult = "";
        boolean compOk = true;
        if (!cmp.isCompareErrorResponses() && (srcStatus >= 500 || tgtStatus >= 500)) {
            compResult = srcStatus >= 500
                    ? "Source " + srcStatus + " — server error"
                    : "Target " + tgtStatus + " — server error";
            compOk = false;
        } else {
            List<String> diffs = comparisonService.compare(srcBody, tgtBody, srcStatus, tgtStatus, cmp);
            compResult = String.join("\n", diffs);
            compOk = diffs.isEmpty();
        }
        r.setComparisonResult(compResult);

        // Automation
        List<AssertionLine> assertions = Collections.emptyList();
        if (auto != null) {
            assertions = assertionService.evaluate(
                    auto.getExpectedStatus(), auto.getExpectedBody(),
                    auto.getExpectedHeaders(), tgtStatus, elapsed, tgtBody,
                    targetResp.getHeaders());
        }
        boolean assertOk = assertionService.allPassed(assertions);
        r.setAssertionResult(assertionService.summarize(assertions));

        boolean bothPass = compOk && assertOk;
        r.setStatus(bothPass ? ExecutionStatus.PASSED : ExecutionStatus.FAILED);
        original.setResult(r);

        if (bothPass) progress.recordPassed(group.getName(), original.getId());
        else          progress.recordFailed(group.getName(), original.getId());

        return tgtBody;
    }

    // ── Variable extraction ───────────────────────────────────────────────────

    /**
     * Parse "varName=$.jsonPath, varName2=$.other.path" and extract values from response.
     */
    private void extractVariables(String responseBody, String extractDsl, Map<String, String> variables) {
        if (responseBody == null || responseBody.isBlank()) return;
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            for (String entry : extractDsl.split(",")) {
                entry = entry.trim();
                int eq = entry.indexOf('=');
                if (eq <= 0) continue;
                String varName = entry.substring(0, eq).trim();
                String jsonPath = entry.substring(eq + 1).trim();
                String value = resolveJsonPath(root, jsonPath);
                if (value != null) {
                    variables.put(varName, value);
                }
            }
        } catch (Exception e) {
            // Silently skip extraction errors — response might not be JSON
        }
    }

    /**
     * Simple JsonPath resolver: $.field.nested.path, $.field[0].nested
     */
    private String resolveJsonPath(JsonNode root, String path) {
        if (!path.startsWith("$.")) return null;
        String[] parts = path.substring(2).split("\\.");
        JsonNode node = root;
        for (String part : parts) {
            if (node == null || node.isMissingNode() || node.isNull()) return null;
            if (part.contains("[")) {
                int bracket = part.indexOf('[');
                String field = part.substring(0, bracket);
                int idx = Integer.parseInt(part.substring(bracket + 1, part.indexOf(']')));
                node = node.get(field);
                if (node != null && node.isArray() && idx < node.size()) {
                    node = node.get(idx);
                } else {
                    return null;
                }
            } else {
                node = node.get(part);
            }
        }
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        return node.isTextual() ? node.asText() : node.toString();
    }

    // ── Variable substitution ─────────────────────────────────────────────────

    /**
     * Creates a shallow copy of TC with {{variable}} placeholders resolved.
     * Original TC is not modified — results are written to original.
     */
    private TestCase resolveVariables(TestCase tc, Map<String, String> variables) {
        if (variables.isEmpty()) return tc;

        TestCase copy = new TestCase();
        copy.setId(tc.getId());
        copy.setName(tc.getName());
        copy.setDescription(tc.getDescription());
        copy.setEnabled(tc.isEnabled());
        copy.setVerificationMode(tc.getVerificationMode());
        copy.setPhase(tc.getPhase());
        copy.setMethod(tc.getMethod());
        copy.setEndpoint(substituteVars(tc.getEndpoint(), variables));
        copy.setQueryParams(substituteParams(tc.getQueryParams(), variables));
        copy.setFormParams(substituteParams(tc.getFormParams(), variables));
        copy.setJsonBody(substituteVars(tc.getJsonBody(), variables));
        copy.setHeaders(substituteVars(tc.getHeaders(), variables));
        copy.setAuthor(tc.getAuthor());
        copy.setExtractVariables(tc.getExtractVariables());
        copy.setComparisonConfig(tc.getComparisonConfig());
        copy.setAutomationConfig(tc.getAutomationConfig());
        return copy;
    }

    private String substituteVars(String template, Map<String, String> variables) {
        if (template == null || template.isBlank() || variables.isEmpty()) return template;
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }

    private List<Param> substituteParams(List<Param> params, Map<String, String> variables) {
        if (params == null || params.isEmpty() || variables.isEmpty()) return params;
        return params.stream()
                .map(p -> new Param(
                        substituteVars(p.getKey(), variables),
                        substituteVars(p.getValue(), variables)))
                .collect(Collectors.toList());
    }

    // ── HTTP call ─────────────────────────────────────────────────────────────

    private ResponseEntity<String> callEndpoint(Environment env, TestCase tc, AuthProfile auth) {
        HttpHeaders headers = new HttpHeaders();

        if (env != null && env.getHeaders() != null) {
            for (Param p : env.getHeaders()) {
                if (p.getKey() != null && !p.getKey().isBlank()) {
                    headers.set(p.getKey().trim(), p.getValue() != null ? p.getValue().trim() : "");
                }
            }
        }

        if (tc.getHeaders() != null && !tc.getHeaders().isBlank()) {
            for (String line : tc.getHeaders().split("\n")) {
                int idx = line.indexOf(':');
                if (idx > 0) headers.set(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }
        }

        authService.applyAuth(auth, headers);

        String baseUrl = env != null ? env.getUrl() : "";
        String url = buildUrl(baseUrl, tc);
        Object body = resolveBody(tc, headers);

        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        org.springframework.http.HttpMethod method =
                org.springframework.http.HttpMethod.valueOf(tc.getMethod().name());

        return restTemplate.exchange(url, method, entity, String.class);
    }

    private String buildUrl(String baseUrl, TestCase tc) {
        String url = baseUrl + tc.getEndpoint();
        if (tc.getQueryParams() != null && !tc.getQueryParams().isEmpty()) {
            url += "?" + tc.getQueryParamsAsString();
        }
        return url;
    }

    private Object resolveBody(TestCase tc, HttpHeaders headers) {
        if (tc.getFormParams() != null && !tc.getFormParams().isEmpty()) {
            headers.remove(HttpHeaders.CONTENT_TYPE);
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            org.springframework.util.MultiValueMap<String, String> form =
                    new org.springframework.util.LinkedMultiValueMap<>();
            for (Param p : tc.getFormParams()) form.add(p.getKey(), p.getValue());
            return form;
        }
        if (tc.getJsonBody() != null && !tc.getJsonBody().isBlank()) {
            if (!headers.containsKey(HttpHeaders.CONTENT_TYPE))
                headers.setContentType(MediaType.APPLICATION_JSON);
            return tc.getJsonBody();
        }
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<TestGroup> filterGroups(TestSuite suite, List<String> filter) {
        if (filter == null || filter.isEmpty()) return suite.getTestGroups();
        return suite.getTestGroups().stream()
                .filter(g -> filter.contains(g.getName()))
                .collect(Collectors.toList());
    }

    private AuthProfile findProfile(TestSuite suite, String name) {
        if (name == null || suite.getAuthProfiles() == null) return null;
        return suite.getAuthProfiles().stream()
                .filter(p -> name.equals(p.getName())).findFirst().orElse(null);
    }

    private ComparisonConfig resolveComparison(TestCase tc, TestSuite suite) {
        ComparisonConfig global   = suite.getSettings().getComparisonConfig();
        ComparisonConfig override = tc.getComparisonConfig();
        if (override == null) return global;

        ComparisonConfig merged = new ComparisonConfig();
        String globalFields = global.getIgnoreFieldsRaw() != null ? global.getIgnoreFieldsRaw() : "";
        String tcFields     = override.getIgnoreFieldsRaw() != null ? override.getIgnoreFieldsRaw() : "";
        String mergedFields = globalFields;
        if (!tcFields.isBlank()) {
            mergedFields = globalFields.isBlank() ? tcFields : globalFields + "," + tcFields;
        }
        merged.setIgnoreFieldsRaw(mergedFields);
        merged.setCaseSensitive(override.isCaseSensitive());
        merged.setIgnoreArrayOrder(override.isIgnoreArrayOrder());
        merged.setNumericTolerance(override.getNumericTolerance() > 0
                ? override.getNumericTolerance() : global.getNumericTolerance());
        merged.setCompareErrorResponses(override.isCompareErrorResponses());
        return merged;
    }
}
