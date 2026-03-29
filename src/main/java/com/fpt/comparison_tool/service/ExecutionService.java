package com.fpt.comparison_tool.service;

import com.fpt.comparison_tool.dto.ExecutionProgress;
import com.fpt.comparison_tool.model.*;
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
    private final ExecutorService executor;

    public ExecutionService(RestTemplate restTemplate,
                            AuthService authService,
                            ComparisonService comparisonService,
                            ExecutorService executionExecutor) {
        this.restTemplate      = restTemplate;
        this.authService       = authService;
        this.comparisonService = comparisonService;
        this.executor          = executionExecutor;
    }

    public void startAsync(TestSuite suite, List<String> groupFilter, ExecutionProgress progress) {
        List<TestGroup> groups = filterGroups(suite, groupFilter);
        int total = groups.stream()
                .mapToInt(g -> (int) g.getTestCases().stream().filter(TestCase::isEnabled).count())
                .sum();
        progress.start(total);

        CompletableFuture.runAsync(() -> {
            try {
                ExecutionConfig ec = suite.getSettings().getExecutionConfig();
                ExecutionMode mode = ec.getMode();

                // Resolve source and target environments by name
                Environment sourceEnv = suite.findEnvironment(ec.getSourceEnvironment());
                Environment targetEnv = suite.findEnvironment(ec.getTargetEnvironment());

                for (TestGroup group : groups) {
                    executeGroup(group, suite, mode, sourceEnv, targetEnv, progress);
                }
            } catch (Exception e) {
                progress.abort("Unexpected error: " + e.getMessage());
            } finally {
                if (progress.isRunning()) progress.finish();
            }
        }, executor);
    }

    // ─── Group execution ──────────────────────────────────────────────────────

    private void executeGroup(TestGroup group, TestSuite suite, ExecutionMode mode,
                              Environment sourceEnv, Environment targetEnv,
                              ExecutionProgress progress) {
        List<TestCase> enabled = group.getTestCases().stream()
                .filter(TestCase::isEnabled).collect(Collectors.toList());

        if (mode == ExecutionMode.PARALLEL) {
            List<CompletableFuture<Void>> futures = enabled.stream()
                    .map(tc -> CompletableFuture.runAsync(
                            () -> executeAndRecord(tc, group, suite, sourceEnv, targetEnv, progress),
                            executor))
                    .collect(Collectors.toList());
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } else {
            for (TestCase tc : enabled) {
                executeAndRecord(tc, group, suite, sourceEnv, targetEnv, progress);
            }
        }
    }

    // ─── Single test case ─────────────────────────────────────────────────────

    private void executeAndRecord(TestCase tc, TestGroup group, TestSuite suite,
                                  Environment sourceEnv, Environment targetEnv,
                                  ExecutionProgress progress) {
        ComparisonConfig cmp = resolveComparison(tc, suite);

        try {
            int delayMs = suite.getSettings().getExecutionConfig().getDelayBetweenRequests();

            AuthProfile sourceAuth = findProfile(suite, sourceEnv != null ? sourceEnv.getAuthProfile() : null);
            AuthProfile targetAuth = findProfile(suite, targetEnv != null ? targetEnv.getAuthProfile() : null);

            ResponseEntity<String> sourceResp = callEndpoint(sourceEnv, tc, sourceAuth);
            if (delayMs > 0) Thread.sleep(delayMs);
            ResponseEntity<String> targetResp = callEndpoint(targetEnv, tc, targetAuth);

            int srcStatus = sourceResp.getStatusCode().value();
            int tgtStatus = targetResp.getStatusCode().value();
            String srcBody = sourceResp.getBody();
            String tgtBody = targetResp.getBody();

            // If compareErrorResponses=false (default): treat any 5xx as ERROR
            // — server is broken, comparison result would be meaningless
            if (!cmp.isCompareErrorResponses()) {
                boolean srcServerError = srcStatus >= 500;
                boolean tgtServerError = tgtStatus >= 500;
                if (srcServerError || tgtServerError) {
                    String msg = srcServerError
                            ? "Source returned " + srcStatus + " — server error, cannot compare"
                            : "Target returned " + tgtStatus + " — server error, cannot compare";
                    tc.setResult(new TestResult(ExecutionStatus.ERROR, msg,
                            String.valueOf(srcStatus), String.valueOf(tgtStatus),
                            srcBody, tgtBody, LocalDateTime.now().format(TIMESTAMP_FMT)));
                    progress.recordError(group.getName(), tc.getId());
                    return;
                }
            }

            List<String> diffs = comparisonService.compare(srcBody, tgtBody, srcStatus, tgtStatus, cmp);
            ExecutionStatus status = diffs.isEmpty() ? ExecutionStatus.PASSED : ExecutionStatus.FAILED;

            tc.setResult(new TestResult(status, String.join("\n", diffs),
                    String.valueOf(srcStatus), String.valueOf(tgtStatus),
                    srcBody, tgtBody, LocalDateTime.now().format(TIMESTAMP_FMT)));

            if (status == ExecutionStatus.PASSED) progress.recordPassed(group.getName(), tc.getId());
            else                                   progress.recordFailed(group.getName(), tc.getId());

        } catch (Exception e) {
            tc.setResult(new TestResult(ExecutionStatus.ERROR,
                    "Execution error: " + e.getMessage(),
                    null, null, null, null, LocalDateTime.now().format(TIMESTAMP_FMT)));
            progress.recordError(group.getName(), tc.getId());
        }
    }

    // ─── HTTP call ────────────────────────────────────────────────────────────

    private ResponseEntity<String> callEndpoint(Environment env, TestCase tc, AuthProfile auth) {
        HttpHeaders headers = new HttpHeaders();

        // 1. Apply environment default headers from List<Param>
        if (env != null && env.getHeaders() != null) {
            for (Param p : env.getHeaders()) {
                if (p.getKey() != null && !p.getKey().isBlank()) {
                    headers.set(p.getKey().trim(), p.getValue() != null ? p.getValue().trim() : "");
                }
            }
        }

        // 2. Apply test case custom headers (override env defaults)
        if (tc.getHeaders() != null && !tc.getHeaders().isBlank()) {
            for (String line : tc.getHeaders().split("\n")) {
                int idx = line.indexOf(':');
                if (idx > 0) headers.set(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }
        }

        // 3. Apply auth
        authService.applyAuth(auth, headers);

        // 4. Build URL
        String baseUrl = env != null ? env.getUrl() : "";
        String url = buildUrl(baseUrl, tc);

        // 5. Build body
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

    // ─── Helpers ──────────────────────────────────────────────────────────────

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
        ComparisonConfig global = suite.getSettings().getComparisonConfig();
        ComparisonConfig override = tc.getComparisonConfig();
        if (override == null) return global;

        // Merge: TC override wins for fields that are explicitly set, else fall back to global
        ComparisonConfig merged = new ComparisonConfig();
        merged.setIgnoreFieldsRaw(
            override.getIgnoreFieldsRaw() != null && !override.getIgnoreFieldsRaw().isBlank()
                ? override.getIgnoreFieldsRaw() : global.getIgnoreFieldsRaw());
        merged.setCaseSensitive(override.isCaseSensitive());
        merged.setIgnoreArrayOrder(override.isIgnoreArrayOrder());
        merged.setNumericTolerance(override.getNumericTolerance() > 0
                ? override.getNumericTolerance() : global.getNumericTolerance());
        // compareErrorResponses: TC override only applies if explicitly set (non-null from JSON)
        // Since boolean defaults to false, we check via Jackson's deserialization —
        // but to be safe, treat TC override as authoritative only if TC config is present
        merged.setCompareErrorResponses(override.isCompareErrorResponses());
        return merged;
    }
}