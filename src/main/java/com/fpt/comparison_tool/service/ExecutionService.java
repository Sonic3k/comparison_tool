package com.fpt.comparison_tool.service;

import com.fpt.comparison_tool.model.*;
import com.fpt.comparison_tool.service.AssertionService.AssertionLine;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
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

    // ── Entry point called by TaskQueueService ────────────────────────────────

    public void runTask(TestSuite suite, ExecutionTask task) {
        try {
            ExecutionConfig ec = suite.getSettings().getExecutionConfig();
            // Task-level verificationMode overrides suite-level setting
            VerificationMode taskFilter = task.getVerificationMode() != null
                    ? task.getVerificationMode()
                    : ec.getVerificationMode();

            List<TestGroup> groups = filterGroups(suite, task.getGroupFilter());
            int total = groups.stream()
                    .mapToInt(g -> (int) g.getTestCases().stream()
                            .filter(TestCase::isEnabled)
                            .filter(tc -> !shouldSkip(tc, taskFilter))
                            .count())
                    .sum();

            task.start(total);

            Environment sourceEnv = suite.findEnvironment(ec.getSourceEnvironment());
            Environment targetEnv = suite.findEnvironment(ec.getTargetEnvironment());

            List<TaskGroupResult> groupResults = new ArrayList<>();

            for (TestGroup group : groups) {
                TaskGroupResult gr = executeGroup(group, suite, ec.getMode(),
                        taskFilter, sourceEnv, targetEnv, task);
                groupResults.add(gr);
            }

            task.finish(groupResults);

        } catch (Exception e) {
            task.abort("Unexpected error: " + e.getMessage());
        }
    }

    // ── Group ─────────────────────────────────────────────────────────────────

    private TaskGroupResult executeGroup(TestGroup group, TestSuite suite,
                                         ExecutionMode execMode,
                                         VerificationMode taskFilter,
                                         Environment sourceEnv, Environment targetEnv,
                                         ExecutionTask task) {
        List<TestCase> toRun = group.getTestCases().stream()
                .filter(TestCase::isEnabled)
                .filter(tc -> !shouldSkip(tc, taskFilter))
                .collect(Collectors.toList());

        List<TaskCaseResult> caseResults = Collections.synchronizedList(new ArrayList<>());

        if (execMode == ExecutionMode.PARALLEL) {
            List<CompletableFuture<Void>> futures = toRun.stream()
                    .map(tc -> CompletableFuture.runAsync(() -> {
                        TaskCaseResult cr = executeOne(tc, suite, sourceEnv, targetEnv, task, group.getName());
                        caseResults.add(cr);
                    }, executor))
                    .collect(Collectors.toList());
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } else {
            for (TestCase tc : toRun) {
                caseResults.add(executeOne(tc, suite, sourceEnv, targetEnv, task, group.getName()));
            }
        }

        return new TaskGroupResult(group.getName(), caseResults);
    }

    // ── Single TC ─────────────────────────────────────────────────────────────

    private TaskCaseResult executeOne(TestCase tc, TestSuite suite,
                                      Environment sourceEnv, Environment targetEnv,
                                      ExecutionTask task, String groupName) {
        VerificationMode vm = tc.getVerificationMode() != null
                ? tc.getVerificationMode() : VerificationMode.COMPARISON;
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);

        TaskCaseResult cr = new TaskCaseResult()
                .id(tc.getId()).name(tc.getName()).mode(vm).executedAt(timestamp);

        try {
            int delayMs = suite.getSettings().getExecutionConfig().getDelayBetweenRequests();
            AuthProfile targetAuth = findProfile(suite, targetEnv != null ? targetEnv.getAuthProfile() : null);

            switch (vm) {
                case COMPARISON -> runComparison(tc, suite, sourceEnv, targetEnv, delayMs, cr);
                case AUTOMATION -> runAutomation(tc, suite, targetEnv, targetAuth, cr);
                case BOTH       -> runBoth(tc, suite, sourceEnv, targetEnv, delayMs, cr);
            }
        } catch (Exception e) {
            cr.status(ExecutionStatus.ERROR)
              .modeRun(vm.getValue())
              .comparisonResult("Execution error: " + e.getMessage());
        }

        // Record progress on task
        if (cr.getStatus() == ExecutionStatus.PASSED)      task.recordPassed(groupName, tc.getId());
        else if (cr.getStatus() == ExecutionStatus.ERROR)  task.recordError(groupName, tc.getId());
        else                                               task.recordFailed(groupName, tc.getId());

        return cr;
    }

    // ── COMPARISON ────────────────────────────────────────────────────────────

    private void runComparison(TestCase tc, TestSuite suite,
                               Environment sourceEnv, Environment targetEnv,
                               int delayMs, TaskCaseResult cr) throws Exception {
        ComparisonConfig cmp = resolveComparison(tc, suite);
        AuthProfile srcAuth = findProfile(suite, sourceEnv != null ? sourceEnv.getAuthProfile() : null);
        AuthProfile tgtAuth = findProfile(suite, targetEnv != null ? targetEnv.getAuthProfile() : null);

        ResponseEntity<String> srcResp = callEndpoint(sourceEnv, tc, srcAuth);
        if (delayMs > 0) Thread.sleep(delayMs);
        ResponseEntity<String> tgtResp = callEndpoint(targetEnv, tc, tgtAuth);

        int srcStatus = srcResp.getStatusCode().value();
        int tgtStatus = tgtResp.getStatusCode().value();
        String srcBody = srcResp.getBody();
        String tgtBody = tgtResp.getBody();

        cr.modeRun(VerificationMode.COMPARISON.getValue())
          .sourceStatus(String.valueOf(srcStatus))
          .targetStatus(String.valueOf(tgtStatus))
          .sourceResponse(srcBody)
          .targetResponse(tgtBody);

        if (!cmp.isCompareErrorResponses() && (srcStatus >= 500 || tgtStatus >= 500)) {
            String msg = srcStatus >= 500
                    ? "Source returned " + srcStatus + " — server error, cannot compare"
                    : "Target returned " + tgtStatus + " — server error, cannot compare";
            cr.status(ExecutionStatus.ERROR).comparisonResult(msg);
            return;
        }

        List<String> diffs = comparisonService.compare(srcBody, tgtBody, srcStatus, tgtStatus, cmp);
        cr.status(diffs.isEmpty() ? ExecutionStatus.PASSED : ExecutionStatus.FAILED)
          .comparisonResult(String.join("\n", diffs));
    }

    // ── AUTOMATION ────────────────────────────────────────────────────────────

    private void runAutomation(TestCase tc, TestSuite suite,
                               Environment targetEnv, AuthProfile targetAuth,
                               TaskCaseResult cr) throws Exception {
        AutomationConfig auto = tc.getAutomationConfig();

        long start = System.currentTimeMillis();
        ResponseEntity<String> tgtResp = callEndpoint(targetEnv, tc, targetAuth);
        long elapsed = System.currentTimeMillis() - start;

        int tgtStatus = tgtResp.getStatusCode().value();
        String tgtBody = tgtResp.getBody();

        cr.modeRun(VerificationMode.AUTOMATION.getValue())
          .targetStatus(String.valueOf(tgtStatus))
          .targetResponse(tgtBody);

        List<AssertionLine> assertions = Collections.emptyList();
        if (auto != null) {
            assertions = assertionService.evaluate(
                    auto.getExpectedStatus(), auto.getExpectedBody(),
                    auto.getExpectedHeaders(), tgtStatus, elapsed, tgtBody,
                    tgtResp.getHeaders());
        }

        boolean passed = assertionService.allPassed(assertions);
        cr.status(assertions.isEmpty() ? ExecutionStatus.ERROR :
                  passed ? ExecutionStatus.PASSED : ExecutionStatus.FAILED)
          .assertionResult(assertionService.summarize(assertions));
    }

    // ── BOTH ─────────────────────────────────────────────────────────────────

    private void runBoth(TestCase tc, TestSuite suite,
                         Environment sourceEnv, Environment targetEnv,
                         int delayMs, TaskCaseResult cr) throws Exception {
        ComparisonConfig cmp  = resolveComparison(tc, suite);
        AutomationConfig auto = tc.getAutomationConfig();
        AuthProfile srcAuth = findProfile(suite, sourceEnv != null ? sourceEnv.getAuthProfile() : null);
        AuthProfile tgtAuth = findProfile(suite, targetEnv != null ? targetEnv.getAuthProfile() : null);

        ResponseEntity<String> srcResp = callEndpoint(sourceEnv, tc, srcAuth);
        if (delayMs > 0) Thread.sleep(delayMs);
        long start = System.currentTimeMillis();
        ResponseEntity<String> tgtResp = callEndpoint(targetEnv, tc, tgtAuth);
        long elapsed = System.currentTimeMillis() - start;

        int srcStatus = srcResp.getStatusCode().value();
        int tgtStatus = tgtResp.getStatusCode().value();
        String srcBody = srcResp.getBody();
        String tgtBody = tgtResp.getBody();

        cr.modeRun(VerificationMode.BOTH.getValue())
          .sourceStatus(String.valueOf(srcStatus))
          .targetStatus(String.valueOf(tgtStatus))
          .sourceResponse(srcBody)
          .targetResponse(tgtBody);

        // Comparison
        String compResult = "";
        boolean compOk = true;
        if (!cmp.isCompareErrorResponses() && (srcStatus >= 500 || tgtStatus >= 500)) {
            compResult = srcStatus >= 500 ? "Source " + srcStatus + " — server error"
                                          : "Target " + tgtStatus + " — server error";
            compOk = false;
        } else {
            List<String> diffs = comparisonService.compare(srcBody, tgtBody, srcStatus, tgtStatus, cmp);
            compResult = String.join("\n", diffs);
            compOk = diffs.isEmpty();
        }
        cr.comparisonResult(compResult);

        // Automation
        List<AssertionLine> assertions = Collections.emptyList();
        if (auto != null) {
            assertions = assertionService.evaluate(
                    auto.getExpectedStatus(), auto.getExpectedBody(),
                    auto.getExpectedHeaders(), tgtStatus, elapsed, tgtBody,
                    tgtResp.getHeaders());
        }
        boolean assertOk = assertionService.allPassed(assertions);
        cr.assertionResult(assertionService.summarize(assertions));

        cr.status(compOk && assertOk ? ExecutionStatus.PASSED : ExecutionStatus.FAILED);
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private ResponseEntity<String> callEndpoint(Environment env, TestCase tc, AuthProfile auth) {
        HttpHeaders headers = new HttpHeaders();

        if (env != null && env.getHeaders() != null) {
            for (Param p : env.getHeaders()) {
                if (p.getKey() != null && !p.getKey().isBlank())
                    headers.set(p.getKey().trim(), p.getValue() != null ? p.getValue().trim() : "");
            }
        }

        if (tc.getHeaders() != null && !tc.getHeaders().isBlank()) {
            for (String line : tc.getHeaders().split("\n")) {
                int idx = line.indexOf(':');
                if (idx > 0) headers.set(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }
        }

        authService.applyAuth(auth, headers);

        String url = (env != null ? env.getUrl() : "") + tc.getEndpoint();
        if (tc.getQueryParams() != null && !tc.getQueryParams().isEmpty())
            url += "?" + tc.getQueryParamsAsString();

        Object body = resolveBody(tc, headers);
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        org.springframework.http.HttpMethod method =
                org.springframework.http.HttpMethod.valueOf(tc.getMethod().name());

        return restTemplate.exchange(url, method, entity, String.class);
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

    private boolean shouldSkip(TestCase tc, VerificationMode filter) {
        if (filter == null) return false;
        VerificationMode m = tc.getVerificationMode() != null
                ? tc.getVerificationMode() : VerificationMode.COMPARISON;
        return switch (filter) {
            case COMPARISON -> m == VerificationMode.AUTOMATION;
            case AUTOMATION -> m == VerificationMode.COMPARISON;
            case BOTH       -> m != VerificationMode.BOTH;
        };
    }

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
        String gf = global.getIgnoreFieldsRaw() != null ? global.getIgnoreFieldsRaw() : "";
        String tf = override.getIgnoreFieldsRaw() != null ? override.getIgnoreFieldsRaw() : "";
        merged.setIgnoreFieldsRaw(tf.isBlank() ? gf : (gf.isBlank() ? tf : gf + "," + tf));
        merged.setCaseSensitive(override.isCaseSensitive());
        merged.setIgnoreArrayOrder(override.isIgnoreArrayOrder());
        merged.setNumericTolerance(override.getNumericTolerance() > 0
                ? override.getNumericTolerance() : global.getNumericTolerance());
        merged.setCompareErrorResponses(override.isCompareErrorResponses());
        return merged;
    }
}
