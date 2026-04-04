package com.fpt.comparison_tool.service;

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
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@Service
public class ExecutionService {

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RestTemplate restTemplate;
    private final AuthService authService;
    private final ComparisonService comparisonService;
    private final AssertionService assertionService;
    private final ExecutorService executor;

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
        List<TestGroup> groups = filterGroups(suite, groupFilter);
        ExecutionConfig ec0 = suite.getSettings().getExecutionConfig();
        VerificationMode suiteFilter = ec0.getVerificationMode(); // null = run all

        // Count only TCs that will actually run under the suite filter
        int total = groups.stream()
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

                for (TestGroup group : groups) {
                    executeGroup(group, suite, mode, filter, sourceEnv, targetEnv, progress);
                }
            } catch (Exception e) {
                progress.abort("Unexpected error: " + e.getMessage());
            } finally {
                if (progress.isRunning()) progress.finish();
            }
        }, executor);
    }

    // ── Group ──────────────────────────────────────────────────────────────────

    private void executeGroup(TestGroup group, TestSuite suite, ExecutionMode execMode,
                              VerificationMode suiteFilter,
                              Environment sourceEnv, Environment targetEnv,
                              ExecutionProgress progress) {
        // Filter: skip TCs that don't match suite-level Verification Mode
        // Skipped TCs keep their current result (pending) — no API call made
        List<TestCase> toRun = group.getTestCases().stream()
                .filter(TestCase::isEnabled)
                .filter(tc -> !shouldSkip(tc, suiteFilter))
                .collect(Collectors.toList());

        if (execMode == ExecutionMode.PARALLEL) {
            List<CompletableFuture<Void>> futures = toRun.stream()
                    .map(tc -> CompletableFuture.runAsync(
                            () -> executeAndRecord(tc, group, suite, sourceEnv, targetEnv, progress),
                            executor))
                    .collect(Collectors.toList());
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } else {
            for (TestCase tc : toRun) {
                executeAndRecord(tc, group, suite, sourceEnv, targetEnv, progress);
            }
        }
    }

    /**
     * Should this TC be skipped under the given suite Verification Mode filter?
     *
     * null filter = run all.
     * COMPARISON  = run only comparison + both TCs  (skip automation-only)
     * AUTOMATION  = run only automation + both TCs  (skip comparison-only)
     * BOTH        = run only both TCs               (skip comparison-only and automation-only)
     */
    private boolean shouldSkip(TestCase tc, VerificationMode suiteFilter) {
        if (suiteFilter == null) return false;
        VerificationMode tcMode = tc.getVerificationMode() != null
                ? tc.getVerificationMode() : VerificationMode.COMPARISON;
        return switch (suiteFilter) {
            case COMPARISON -> tcMode == VerificationMode.AUTOMATION;
            case AUTOMATION -> tcMode == VerificationMode.COMPARISON;
            case BOTH       -> tcMode != VerificationMode.BOTH;
        };
    }

    // ── Single TC ──────────────────────────────────────────────────────────────

    private void executeAndRecord(TestCase tc, TestGroup group, TestSuite suite,
                                  Environment sourceEnv, Environment targetEnv,
                                  ExecutionProgress progress) {
        // TC has already been filtered by shouldSkip() — use its own VerificationMode
        VerificationMode verificationMode = tc.getVerificationMode() != null
                ? tc.getVerificationMode() : VerificationMode.COMPARISON;
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);

        try {
            int delayMs = suite.getSettings().getExecutionConfig().getDelayBetweenRequests();
            AuthProfile targetAuth = findProfile(suite, targetEnv != null ? targetEnv.getAuthProfile() : null);

            switch (verificationMode) {
                case COMPARISON -> runComparison(tc, group, suite, sourceEnv, targetEnv, progress, delayMs, timestamp);
                case AUTOMATION -> runAutomation(tc, group, suite, targetEnv, targetAuth, progress, timestamp);
                case BOTH       -> runBoth(tc, group, suite, sourceEnv, targetEnv, progress, delayMs, timestamp);
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

    // ── COMPARISON mode ────────────────────────────────────────────────────────

    private void runComparison(TestCase tc, TestGroup group, TestSuite suite,
                               Environment sourceEnv, Environment targetEnv,
                               ExecutionProgress progress, int delayMs, String timestamp) throws Exception {
        ComparisonConfig cmp = resolveComparison(tc, suite);

        AuthProfile sourceAuth = findProfile(suite, sourceEnv != null ? sourceEnv.getAuthProfile() : null);
        AuthProfile targetAuth = findProfile(suite, targetEnv != null ? targetEnv.getAuthProfile() : null);

        ResponseEntity<String> sourceResp = callEndpoint(sourceEnv, tc, sourceAuth);
        if (delayMs > 0) Thread.sleep(delayMs);
        ResponseEntity<String> targetResp = callEndpoint(targetEnv, tc, targetAuth);

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
            tc.setResult(r);
            progress.recordError(group.getName(), tc.getId());
            return;
        }

        List<String> diffs = comparisonService.compare(srcBody, tgtBody, srcStatus, tgtStatus, cmp);
        r.setStatus(diffs.isEmpty() ? ExecutionStatus.PASSED : ExecutionStatus.FAILED);
        r.setComparisonResult(String.join("\n", diffs));
        tc.setResult(r);

        if (r.getStatus() == ExecutionStatus.PASSED) progress.recordPassed(group.getName(), tc.getId());
        else                                          progress.recordFailed(group.getName(), tc.getId());
    }

    // ── AUTOMATION mode ────────────────────────────────────────────────────────

    private void runAutomation(TestCase tc, TestGroup group, TestSuite suite,
                               Environment targetEnv, AuthProfile targetAuth,
                               ExecutionProgress progress, String timestamp) throws Exception {
        AutomationConfig auto = tc.getAutomationConfig();

        long start = System.currentTimeMillis();
        ResponseEntity<String> targetResp = callEndpoint(targetEnv, tc, targetAuth);
        long elapsed = System.currentTimeMillis() - start;

        int tgtStatus = targetResp.getStatusCode().value();
        String tgtBody = targetResp.getBody();

        TestResult r = new TestResult();
        r.setModeRun(VerificationMode.AUTOMATION.getValue());
        r.setTargetStatus(String.valueOf(tgtStatus));
        r.setTargetResponse(tgtBody);
        r.setExecutedAt(timestamp);

        // Run assertions
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
        tc.setResult(r);

        if (passed) progress.recordPassed(group.getName(), tc.getId());
        else        progress.recordFailed(group.getName(), tc.getId());
    }

    // ── BOTH mode ──────────────────────────────────────────────────────────────

    private void runBoth(TestCase tc, TestGroup group, TestSuite suite,
                         Environment sourceEnv, Environment targetEnv,
                         ExecutionProgress progress, int delayMs, String timestamp) throws Exception {
        ComparisonConfig cmp = resolveComparison(tc, suite);
        AutomationConfig auto = tc.getAutomationConfig();

        AuthProfile sourceAuth = findProfile(suite, sourceEnv != null ? sourceEnv.getAuthProfile() : null);
        AuthProfile targetAuth = findProfile(suite, targetEnv != null ? targetEnv.getAuthProfile() : null);

        ResponseEntity<String> sourceResp = callEndpoint(sourceEnv, tc, sourceAuth);
        if (delayMs > 0) Thread.sleep(delayMs);
        long start = System.currentTimeMillis();
        ResponseEntity<String> targetResp = callEndpoint(targetEnv, tc, targetAuth);
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

        // Overall status: pass only if both pass
        boolean bothPass = compOk && assertOk;
        r.setStatus(bothPass ? ExecutionStatus.PASSED : ExecutionStatus.FAILED);
        tc.setResult(r);

        if (bothPass) progress.recordPassed(group.getName(), tc.getId());
        else          progress.recordFailed(group.getName(), tc.getId());
    }

    // ── HTTP call (unchanged) ──────────────────────────────────────────────────

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

    // ── Helpers ────────────────────────────────────────────────────────────────

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
