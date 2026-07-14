package com.fpt.comparison_tool.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.comparison_tool.dto.ExecutionProgress;
import com.fpt.comparison_tool.dto.ExecutionStartRequest;
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
import java.util.concurrent.Executors;
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

    /** Dedicated thread for run orchestration — keeps the request pool fully
     *  available for test-case chunks and rules out join-starvation. */
    private final ExecutorService orchestrator = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "exec-orchestrator");
        t.setDaemon(true);
        return t;
    });
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

    public void startAsync(TestSuite suite, ExecutionStartRequest request, ExecutionProgress progress) {
        ResolvedScope scope;
        List<String> plannedKeys;
        try {
            suite.normalize();
            scope = resolveScope(suite, request);
            if (scope.emptyReason() != null) {
                progress.abort(scope.emptyReason());
                return;
            }

            VerificationMode suiteFilter = suite.getSettings().getExecutionConfig().getVerificationMode();
            plannedKeys = new ArrayList<>();
            for (TestGroup g : scope.groups()) {
                for (TestRequest r : runnableRequests(g, suiteFilter, scope.filterFor(g), scope.runSetupTeardown(g))) {
                    plannedKeys.add(ExecutionProgress.key(g.getName(), r.getId()));
                }
            }
            if (plannedKeys.isEmpty()) {
                progress.abort("Nothing to run for the requested scope");
                return;
            }
        } catch (Exception e) {
            // Setup failed before we even started — make sure flag is clean
            progress.abort("Setup error: " + e.getMessage());
            return;
        }

        progress.start(plannedKeys.size(), plannedKeys);

        final ResolvedScope fScope = scope;
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
                List<TestGroup> setupGroups    = new ArrayList<>();
                List<TestGroup> normalGroups   = new ArrayList<>();
                List<TestGroup> teardownGroups = new ArrayList<>();

                for (TestGroup g : fScope.groups()) {
                    if (g.getName().startsWith(GLOBAL_SETUP_PREFIX))         setupGroups.add(g);
                    else if (g.getName().startsWith(GLOBAL_TEARDOWN_PREFIX)) teardownGroups.add(g);
                    else                                                     normalGroups.add(g);
                }

                // 1. Global Setup groups — sequential, variables go to suiteVars
                for (TestGroup g : setupGroups) {
                    if (progress.isStopRequested()) break;
                    executeGroup(g, suite, mode, filter, sourceEnv, targetEnv, progress, suiteVars, null, true);
                }

                // 2. Normal groups — each gets its own groupVars (initialized with suiteVars)
                if (!progress.isStopRequested()) {
                    for (TestGroup g : normalGroups) {
                        if (progress.isStopRequested()) break;
                        Map<String, String> groupVars = new ConcurrentHashMap<>(suiteVars);
                        executeGroup(g, suite, mode, filter, sourceEnv, targetEnv, progress, groupVars,
                                fScope.filterFor(g), fScope.runSetupTeardown(g));
                    }
                }

                // 3. Global Teardown groups — sequential, ALWAYS run (even after stop)
                for (TestGroup g : teardownGroups) {
                    executeGroup(g, suite, mode, filter, sourceEnv, targetEnv, progress, suiteVars, null, true);
                }

            } catch (Exception e) {
                progress.abort("Unexpected error: " + e.getMessage());
            } finally {
                if (progress.isRunning()) progress.finish();
            }
        }, orchestrator).exceptionally(ex -> {
            // Reached only if the executor refuses the task — make sure flag is cleared
            progress.abort("Executor rejected: " + ex.getMessage());
            return null;
        });
    }

    // ── Scope resolution ──────────────────────────────────────────────────────

    /**
     * Resolved execution scope.
     *
     * tcFilter == null            → full run (all/groups scope)
     * tcFilter per group non-null → only TEST-phase requests whose testCaseId
     *                               is in the set run; setup/teardown phases of
     *                               that group run iff includeSetup.
     */
    private record ResolvedScope(List<TestGroup> groups,
                                 Map<String, Set<String>> tcFilter,
                                 boolean includeSetup,
                                 String emptyReason) {

        Set<String> filterFor(TestGroup g) {
            return tcFilter == null ? null : tcFilter.get(g.getName());
        }

        boolean runSetupTeardown(TestGroup g) {
            if (tcFilter == null) return true;
            if (g.getName().startsWith(GLOBAL_SETUP_PREFIX)
                    || g.getName().startsWith(GLOBAL_TEARDOWN_PREFIX)) return true;
            return includeSetup;
        }
    }

    private ResolvedScope resolveScope(TestSuite suite, ExecutionStartRequest request) {
        String scope = request != null && request.getScope() != null
                ? request.getScope().toLowerCase().trim() : "";
        boolean includeSetup = request == null
                || request.getIncludeSetup() == null || request.getIncludeSetup();

        List<ExecutionStartRequest.TestCaseRef> refs = null;
        if ("failed".equals(scope)) {
            refs = collectFailedTestCases(suite);
            if (refs.isEmpty()) {
                return new ResolvedScope(List.of(), Map.of(), includeSetup,
                        "No failed test cases to re-run");
            }
        } else if ("testcases".equals(scope)
                || (request != null && request.getTestCases() != null && !request.getTestCases().isEmpty())) {
            refs = request != null ? request.getTestCases() : null;
            if (refs == null || refs.isEmpty()) {
                return new ResolvedScope(List.of(), Map.of(), includeSetup,
                        "No test cases specified");
            }
        }

        if (refs != null) {
            Map<String, Set<String>> byGroup = new LinkedHashMap<>();
            for (ExecutionStartRequest.TestCaseRef ref : refs) {
                if (ref.getGroupName() == null || ref.getTestCaseId() == null) continue;
                byGroup.computeIfAbsent(ref.getGroupName(), k -> new LinkedHashSet<>())
                       .add(ref.getTestCaseId());
            }
            List<TestGroup> groups = new ArrayList<>();
            for (TestGroup g : suite.getTestGroups()) {
                boolean isGlobal = g.getName().startsWith(GLOBAL_SETUP_PREFIX)
                        || g.getName().startsWith(GLOBAL_TEARDOWN_PREFIX);
                if (byGroup.containsKey(g.getName()) || (isGlobal && includeSetup)) {
                    groups.add(g);
                }
            }
            return new ResolvedScope(groups, byGroup, includeSetup, null);
        }

        // all / groups scope — legacy body { "groups": [...] } lands here
        List<String> groupNames = request != null ? request.getGroups() : null;
        return new ResolvedScope(filterGroups(suite, groupNames), null, true, null);
    }

    /** Every enabled test case whose rolled-up status is failed/error. */
    private List<ExecutionStartRequest.TestCaseRef> collectFailedTestCases(TestSuite suite) {
        List<ExecutionStartRequest.TestCaseRef> refs = new ArrayList<>();
        for (TestGroup g : suite.getTestGroups()) {
            if (!g.isEnabled()) continue;
            if (g.getName().startsWith(GLOBAL_SETUP_PREFIX)
                    || g.getName().startsWith(GLOBAL_TEARDOWN_PREFIX)) continue;
            Set<String> added = new LinkedHashSet<>();
            for (TestRequest r : g.getTestRequests()) {
                if (!r.isEnabled() || r.getResult() == null || r.getResult().getStatus() == null) continue;
                ExecutionStatus st = r.getResult().getStatus();
                if (st == ExecutionStatus.FAILED || st == ExecutionStatus.ERROR) {
                    String tcId = r.getTestCaseId() != null ? r.getTestCaseId() : r.getId();
                    if (added.add(tcId)) {
                        refs.add(new ExecutionStartRequest.TestCaseRef(g.getName(), tcId));
                    }
                }
            }
        }
        return refs;
    }

    /** The requests of a group that will actually execute under the given scope. */
    private List<TestRequest> runnableRequests(TestGroup group, VerificationMode suiteFilter,
                                               Set<String> tcFilter, boolean runSetupTeardown) {
        List<TestRequest> out = new ArrayList<>();
        for (TestRequest r : group.getTestRequests()) {
            if (!r.isEnabled() || shouldSkip(r, suiteFilter)) continue;
            boolean setupOrTeardown = r.getPhase() == Phase.SETUP || r.getPhase() == Phase.TEARDOWN;
            if (setupOrTeardown) {
                if (runSetupTeardown) out.add(r);
            } else if (tcFilter == null
                    || tcFilter.contains(r.getTestCaseId() != null ? r.getTestCaseId() : r.getId())) {
                out.add(r);
            }
        }
        return out;
    }

    // ── Group execution with phases ───────────────────────────────────────────

    private void executeGroup(TestGroup group, TestSuite suite, ExecutionMode execMode,
                              VerificationMode suiteFilter,
                              Environment sourceEnv, Environment targetEnv,
                              ExecutionProgress progress,
                              Map<String, String> variables,
                              Set<String> tcFilter, boolean runSetupTeardown) {

        List<TestRequest> runnable = runnableRequests(group, suiteFilter, tcFilter, runSetupTeardown);

        // Split by phase
        List<TestRequest> setupReqs    = runnable.stream().filter(r -> r.getPhase() == Phase.SETUP).collect(Collectors.toList());
        List<TestRequest> testReqs     = runnable.stream().filter(r -> r.getPhase() == Phase.TEST).collect(Collectors.toList());
        List<TestRequest> teardownReqs = runnable.stream().filter(r -> r.getPhase() == Phase.TEARDOWN).collect(Collectors.toList());

        // 1. Setup — always sequential
        for (TestRequest r : setupReqs) {
            if (progress.isStopRequested()) break;
            executeAndRecord(r, group, suite, sourceEnv, targetEnv, progress, variables);
        }

        // 2. Test — requests of the SAME test case always run sequentially in
        //    declared order (they may depend on each other via extractVariables);
        //    different test cases run in parallel when mode = PARALLEL.
        if (!progress.isStopRequested()) {
            Map<String, List<TestRequest>> chunks = new LinkedHashMap<>();
            for (TestRequest r : testReqs) {
                String tcId = r.getTestCaseId() != null ? r.getTestCaseId() : r.getId();
                chunks.computeIfAbsent(tcId, k -> new ArrayList<>()).add(r);
            }

            if (execMode == ExecutionMode.PARALLEL && chunks.size() > 1) {
                List<CompletableFuture<Void>> futures = chunks.values().stream()
                        .map(chunk -> CompletableFuture.runAsync(() -> {
                            for (TestRequest r : chunk) {
                                if (progress.isStopRequested()) return;
                                executeAndRecord(r, group, suite, sourceEnv, targetEnv, progress, variables);
                            }
                        }, executor))
                        .collect(Collectors.toList());
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } else {
                for (TestRequest r : testReqs) {
                    if (progress.isStopRequested()) break;
                    executeAndRecord(r, group, suite, sourceEnv, targetEnv, progress, variables);
                }
            }
        }

        // 3. Teardown — always sequential, ALWAYS runs (even after stop)
        for (TestRequest r : teardownReqs) {
            executeAndRecord(r, group, suite, sourceEnv, targetEnv, progress, variables);
        }
    }

    /**
     * Should this TC be skipped under the given suite Verification Mode filter?
     *
     * null filter = run all.
     * Setup/teardown phase TCs always run regardless of filter.
     */
    private boolean shouldSkip(TestRequest tc, VerificationMode suiteFilter) {
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

    /**
     * Re-run one TC synchronously. Used for the "↻" button on each row in the
     * detail view — does NOT touch the suite-wide ExecutionProgress, does NOT
     * go through the async queue, does NOT run setup/teardown.
     *
     * Returns the TC with its result freshly updated, or throws if the TC
     * cannot be located in the suite.
     */
    public TestRequest executeSingleSync(TestSuite suite, String groupName, String caseId) {
        TestGroup group = suite.getTestGroups().stream()
                .filter(g -> g.getName().equals(groupName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Group not found: " + groupName));

        TestRequest tc = group.getTestRequests().stream()
                .filter(c -> c.getId().equals(caseId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        ExecutionConfig ec = suite.getSettings().getExecutionConfig();
        Environment sourceEnv = suite.findEnvironment(ec.getSourceEnvironment());
        Environment targetEnv = suite.findEnvironment(ec.getTargetEnvironment());

        // No flow variables on a single re-run — session globals (populated by
        // earlier runs, editable in the Variables panel) fill the placeholders
        Map<String, String> variables = new ConcurrentHashMap<>();

        // Use a throwaway progress so executeAndRecord doesn't touch the real one
        ExecutionProgress dummy = new ExecutionProgress();
        dummy.start(1);
        executeAndRecord(tc, group, suite, sourceEnv, targetEnv, dummy, variables);
        dummy.finish();

        return tc;
    }

    private void executeAndRecord(TestRequest tc, TestGroup group, TestSuite suite,
                                  Environment sourceEnv, Environment targetEnv,
                                  ExecutionProgress progress,
                                  Map<String, String> variables) {
        VerificationMode verificationMode = tc.getVerificationMode() != null
                ? tc.getVerificationMode() : VerificationMode.COMPARISON;
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);

        progress.recordStart(group.getName(), tc);

        // Resolve {{variables}} in TC fields before execution.
        // Precedence: flow variables (this run's extracts) > session globals;
        // environment variables are applied last, per side, in callEndpoint.
        TestRequest resolved = resolveVariables(tc, variables);
        resolved = resolveVariables(resolved, globalVarMap(suite));

        try {
            int delayMs = suite.getSettings().getExecutionConfig().getDelayBetweenRequests();
            AuthProfile targetAuth = resolveAuthProfile(suite, tc, targetEnv);

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
                extractVariables(responseBody, tc.getExtractVariables(), variables, suite);
            }

        } catch (Exception e) {
            String reason = errorReason(e);
            TestResult r = new TestResult();
            r.setStatus(ExecutionStatus.ERROR);
            r.setModeRun(verificationMode.getValue());
            r.setErrorMessage(reason);
            r.setComparisonResult(reason);   // also lands in the Excel "Differences" column
            r.setExecutedAt(timestamp);
            tc.setResult(r);
            progress.recordError(group.getName(), tc);
        }
    }

    /** Human-readable reason for a transport/infrastructure failure. */
    private String errorReason(Exception e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String base = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (c instanceof java.net.SocketTimeoutException)
            return "⏱ Timeout — no response within the configured timeout. " + base;
        if (c instanceof java.net.UnknownHostException)
            return "🌐 Unknown host — the URL's domain cannot be resolved (check Environment URL): " + c.getMessage();
        if (c instanceof java.net.ConnectException)
            return "🔌 Connection failed — host unreachable or port closed. " + base;
        if (c instanceof javax.net.ssl.SSLException)
            return "🔒 SSL error: " + base;
        return "Execution error: " + base;
    }

    // ── NONE mode ─────────────────────────────────────────────────────────────

    private String runNone(TestRequest resolved, TestRequest original, TestGroup group, TestSuite suite,
                           Environment targetEnv, AuthProfile targetAuth,
                           ExecutionProgress progress, String timestamp) throws Exception {
        long tgtStart = System.currentTimeMillis();
        ResponseEntity<String> targetResp = callEndpoint(targetEnv, resolved, targetAuth);
        long tgtTime = System.currentTimeMillis() - tgtStart;

        int tgtStatus = targetResp.getStatusCode().value();
        String tgtBody = targetResp.getBody();

        TestResult r = new TestResult();
        r.setModeRun(VerificationMode.NONE.getValue());
        r.setTargetTimeMs(tgtTime);
        r.setTargetStatus(String.valueOf(tgtStatus));
        r.setTargetResponse(tgtBody);
        r.setExecutedAt(timestamp);
        r.setStatus(tgtStatus < 400 ? ExecutionStatus.PASSED : ExecutionStatus.ERROR);
        r.setComparisonResult(tgtStatus >= 400 ? "Target returned " + tgtStatus : "");
        original.setResult(r);

        if (r.getStatus() == ExecutionStatus.PASSED) progress.recordPassed(group.getName(), original);
        else                                          progress.recordError(group.getName(), original);

        return tgtBody;
    }

    // ── COMPARISON mode ───────────────────────────────────────────────────────

    private void runComparison(TestRequest resolved, TestRequest original, TestGroup group, TestSuite suite,
                               Environment sourceEnv, Environment targetEnv,
                               ExecutionProgress progress, int delayMs, String timestamp) throws Exception {
        ComparisonConfig cmp = resolveComparison(original, suite);

        AuthProfile sourceAuth = resolveAuthProfile(suite, original, sourceEnv);
        AuthProfile targetAuth = resolveAuthProfile(suite, original, targetEnv);

        long srcStart = System.currentTimeMillis();
        ResponseEntity<String> sourceResp = callEndpoint(sourceEnv, resolved, sourceAuth);
        long srcTime = System.currentTimeMillis() - srcStart;
        if (delayMs > 0) Thread.sleep(delayMs);
        long tgtStart = System.currentTimeMillis();
        ResponseEntity<String> targetResp = callEndpoint(targetEnv, resolved, targetAuth);
        long tgtTime = System.currentTimeMillis() - tgtStart;

        int srcStatus = sourceResp.getStatusCode().value();
        int tgtStatus = targetResp.getStatusCode().value();
        String srcBody = sourceResp.getBody();
        String tgtBody = targetResp.getBody();

        TestResult r = new TestResult();
        r.setModeRun(VerificationMode.COMPARISON.getValue());
        r.setSourceTimeMs(srcTime);
        r.setTargetTimeMs(tgtTime);
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
            progress.recordError(group.getName(), original);
            return;
        }

        List<String> diffs = comparisonService.compare(srcBody, tgtBody, srcStatus, tgtStatus, cmp);
        r.setStatus(diffs.isEmpty() ? ExecutionStatus.PASSED : ExecutionStatus.FAILED);
        r.setComparisonResult(String.join("\n", diffs));
        original.setResult(r);

        if (r.getStatus() == ExecutionStatus.PASSED) progress.recordPassed(group.getName(), original);
        else                                          progress.recordFailed(group.getName(), original);
    }

    // ── AUTOMATION mode ───────────────────────────────────────────────────────

    private String runAutomation(TestRequest resolved, TestRequest original, TestGroup group, TestSuite suite,
                                 Environment targetEnv, AuthProfile targetAuth,
                                 ExecutionProgress progress, String timestamp) throws Exception {
        AutomationConfig auto = substituteAutomation(resolved.getAutomationConfig(), envVarMap(targetEnv));

        long start = System.currentTimeMillis();
        ResponseEntity<String> targetResp = callEndpoint(targetEnv, resolved, targetAuth);
        long elapsed = System.currentTimeMillis() - start;

        int tgtStatus = targetResp.getStatusCode().value();
        String tgtBody = targetResp.getBody();

        TestResult r = new TestResult();
        r.setModeRun(VerificationMode.AUTOMATION.getValue());
        r.setTargetTimeMs(elapsed);
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

        if (passed) progress.recordPassed(group.getName(), original);
        else        progress.recordFailed(group.getName(), original);

        return tgtBody;
    }

    // ── BOTH mode ─────────────────────────────────────────────────────────────

    private String runBoth(TestRequest resolved, TestRequest original, TestGroup group, TestSuite suite,
                           Environment sourceEnv, Environment targetEnv,
                           ExecutionProgress progress, int delayMs, String timestamp) throws Exception {
        ComparisonConfig cmp = resolveComparison(original, suite);
        AutomationConfig auto = substituteAutomation(resolved.getAutomationConfig(), envVarMap(targetEnv));

        AuthProfile sourceAuth = resolveAuthProfile(suite, original, sourceEnv);
        AuthProfile targetAuth = resolveAuthProfile(suite, original, targetEnv);

        long srcStart = System.currentTimeMillis();
        ResponseEntity<String> sourceResp = callEndpoint(sourceEnv, resolved, sourceAuth);
        long srcTime = System.currentTimeMillis() - srcStart;
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
        r.setSourceTimeMs(srcTime);
        r.setTargetTimeMs(elapsed);
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

        if (bothPass) progress.recordPassed(group.getName(), original);
        else          progress.recordFailed(group.getName(), original);

        return tgtBody;
    }

    // ── Variable extraction ───────────────────────────────────────────────────

    /**
     * Parse "varName=$.jsonPath, varName2=$.other.path" and extract values from response.
     */
    private static final java.util.regex.Pattern VAR_TOKEN =
            java.util.regex.Pattern.compile("\\{\\{([A-Za-z0-9_]+)\\}\\}");

    /**
     * Any {{var}} still present after substitution means the variable was never
     * produced (extracting request failed/skipped, or its extractVariables did
     * not match the response). Failing here gives a readable reason instead of
     * Spring's \"Not enough variable values available to expand '{...'\".
     */
    private void failOnUnresolvedVars(TestRequest r) {
        Set<String> unresolved = new LinkedHashSet<>();
        collectVars(r.getEndpoint(), unresolved);
        collectVars(r.getHeaders(), unresolved);
        collectVars(r.getJsonBody(), unresolved);
        if (r.getQueryParams() != null) r.getQueryParams().forEach(p2 -> collectVars(p2.getValue(), unresolved));
        if (r.getFormParams()  != null) r.getFormParams().forEach(p2 -> collectVars(p2.getValue(), unresolved));
        if (unresolved.isEmpty()) return;
        throw new IllegalStateException("Unresolved variables " + unresolved
                + " — the request that extracts them failed or was skipped, "
                + "or its extractVariables didn't match the response "
                + "(format: one var=$.json.path per line)");
    }

    private void collectVars(String text, Set<String> out) {
        if (text == null || text.isEmpty()) return;
        java.util.regex.Matcher m = VAR_TOKEN.matcher(text);
        while (m.find()) out.add("{{" + m.group(1) + "}}");
    }

    private Map<String, String> globalVarMap(TestSuite suite) {
        Map<String, String> m = new LinkedHashMap<>();
        if (suite != null && suite.getGlobalVariables() != null) {
            for (GlobalVariable v : suite.getGlobalVariables()) {
                if (v.getName() != null && !v.getName().isBlank()) {
                    m.put(v.getName().trim(), v.getValue() != null ? v.getValue() : "");
                }
            }
        }
        return m;
    }

    private void extractVariables(String responseBody, String extractDsl, Map<String, String> variables, TestSuite suite) {
        if (responseBody == null || responseBody.isBlank()) return;
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            // Documented format: one "var=$.path" per line; commas also accepted
            for (String entry : extractDsl.split("\\r?\\n|,")) {
                entry = entry.trim();
                int eq = entry.indexOf('=');
                if (eq <= 0) continue;
                String varName = entry.substring(0, eq).trim();
                String jsonPath = entry.substring(eq + 1).trim();
                String value = resolveJsonPath(root, jsonPath);
                if (value != null) {
                    variables.put(varName, value);
                    // Write-through to the session-global store (visible in the
                    // Variables panel; makes single-request re-runs resolvable)
                    suite.putGlobalVariable(varName, value, LocalDateTime.now().format(TIMESTAMP_FMT));
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
    private TestRequest resolveVariables(TestRequest tc, Map<String, String> variables) {
        if (variables.isEmpty()) return tc;

        TestRequest copy = new TestRequest();
        copy.setId(tc.getId());
        copy.setTestCaseId(tc.getTestCaseId());
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
        copy.setAuthProfile(tc.getAuthProfile());
        copy.setExtractVariables(tc.getExtractVariables());
        copy.setComparisonConfig(tc.getComparisonConfig());
        copy.setAutomationConfig(substituteAutomation(tc.getAutomationConfig(), variables));
        return copy;
    }

    private AutomationConfig substituteAutomation(AutomationConfig source, Map<String, String> variables) {
        if (source == null) return null;
        AutomationConfig copy = new AutomationConfig();
        copy.setExpectedStatus(substituteVars(source.getExpectedStatus(), variables));
        copy.setExpectedBody(substituteVars(source.getExpectedBody(), variables));
        copy.setExpectedHeaders(substituteVars(source.getExpectedHeaders(), variables));
        copy.setMaxResponseTime(source.getMaxResponseTime());
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

    /**
     * Variables defined on the environment, plus the implicit {{baseUrl}}
     * (the environment's URL) unless the environment defines its own.
     */
    private Map<String, String> envVarMap(Environment env) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (env != null && env.getVariables() != null) {
            for (Param p : env.getVariables()) {
                if (p.getKey() != null && !p.getKey().isBlank()) {
                    vars.put(p.getKey().trim(), p.getValue() != null ? p.getValue() : "");
                }
            }
        }
        if (env != null && env.getUrl() != null && !env.getUrl().isBlank() && !vars.containsKey("baseUrl")) {
            vars.put("baseUrl", env.getUrl());
        }
        return vars;
    }

    /** Request-level auth override; falls back to the environment's profile. */
    private AuthProfile resolveAuthProfile(TestSuite suite, TestRequest tc, Environment env) {
        String name = tc != null && tc.getAuthProfile() != null && !tc.getAuthProfile().isBlank()
                ? tc.getAuthProfile()
                : (env != null ? env.getAuthProfile() : null);
        return findProfile(suite, name);
    }

    private ResponseEntity<String> callEndpoint(Environment env, TestRequest tc, AuthProfile auth) {
        // Env-level variables are applied per side, after runtime variables;
        // only then can "still unresolved" be judged fairly.
        tc = resolveVariables(tc, envVarMap(env));
        failOnUnresolvedVars(tc);

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

    private String buildUrl(String baseUrl, TestRequest tc) {
        String ep  = tc.getEndpoint() != null ? tc.getEndpoint() : "";
        String url = ep.startsWith("http://") || ep.startsWith("https://") ? ep : baseUrl + ep;
        if (tc.getQueryParams() != null && !tc.getQueryParams().isEmpty()) {
            url += "?" + tc.getQueryParamsAsString();
        }
        return url;
    }

    private Object resolveBody(TestRequest tc, HttpHeaders headers) {
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

    private ComparisonConfig resolveComparison(TestRequest tc, TestSuite suite) {
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
