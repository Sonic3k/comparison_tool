package com.fpt.comparison_tool.controller;

import com.fpt.comparison_tool.dto.ApiResponse;
import com.fpt.comparison_tool.dto.ExecutionProgress;
import com.fpt.comparison_tool.dto.ExecutionStartRequest;
import com.fpt.comparison_tool.model.*;
import com.fpt.comparison_tool.service.CurlBuilder;
import com.fpt.comparison_tool.service.ExecutionService;
import com.fpt.comparison_tool.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/execute")
public class ExecutionController {

    private final SessionService session;
    private final ExecutionService executionService;
    private final CurlBuilder curlBuilder;

    public ExecutionController(SessionService session,
                               ExecutionService executionService,
                               CurlBuilder curlBuilder) {
        this.session          = session;
        this.executionService = executionService;
        this.curlBuilder      = curlBuilder;
    }

    /**
     * POST /api/execute
     *
     * Body (all fields optional):
     *   { "groups": ["User APIs"] }                                → run only these groups (legacy shape, unchanged)
     *   { }                                                        → run everything
     *   { "scope": "failed", "includeSetup": true }                → re-run every failed/error test case
     *   { "scope": "testcases", "includeSetup": true,
     *     "testCases": [ { "groupName": "User APIs",
     *                      "testCaseId": "TC002" } ] }             → run selected test cases (all their requests, in order)
     *
     * includeSetup (default true) wraps scoped runs with the group's
     * setup/teardown phases and any Global Setup/Teardown groups, so
     * {{variables}} resolve exactly like in a full run.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> startExecution(
            @RequestBody(required = false) ExecutionStartRequest body) {
        if (!session.hasSuite()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("No suite loaded"));
        }
        ExecutionProgress progress = session.getProgress();
        if (progress.isRunning() && !progress.isCompleted()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Execution already running"));
        }
        progress.reset();
        executionService.startAsync(session.getTestSuite(), body, progress);
        return ResponseEntity.ok(ApiResponse.ok("Execution started", null));
    }

    /**
     * POST /api/execute/stop
     *
     * Graceful stop: requests already in flight finish, everything not yet
     * started is skipped, teardown phases and Global Teardown groups still run.
     */
    @PostMapping("/stop")
    public ResponseEntity<ApiResponse<Void>> stopExecution() {
        ExecutionProgress progress = session.getProgress();
        if (!progress.isRunning()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("No execution running"));
        }
        progress.requestStop();
        return ResponseEntity.ok(ApiResponse.ok(
                "Stop requested — in-flight requests will finish, teardown will still run", null));
    }

    /**
     * POST /api/execute/case
     * Body: { "groupName": "User APIs", "caseId": "TC001" }
     *
     * Re-runs a single request synchronously and returns it with fresh result.
     * Does not affect the suite-wide ExecutionProgress, does not run
     * setup/teardown and has no extracted variables — {{placeholders}} stay
     * literal. Kept for the legacy UI; prefer scope=testcases for anything
     * that depends on setup or variables.
     */
    @PostMapping("/case")
    public ResponseEntity<ApiResponse<TestRequest>> runSingleCase(
            @RequestBody Map<String, String> body) {
        if (!session.hasSuite()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("No suite loaded"));
        }
        String groupName = body.get("groupName");
        String caseId    = body.get("caseId");
        if (groupName == null || caseId == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("groupName and caseId are required"));
        }
        try {
            TestRequest tc = executionService.executeSingleSync(session.getTestSuite(), groupName, caseId);
            return ResponseEntity.ok(ApiResponse.ok("Re-run complete", tc));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Re-run failed: " + e.getMessage()));
        }
    }

    /**
     * GET /api/execute/case/curl?groupName=...&caseId=...
     *
     * Returns the cURL commands that would actually be sent against source and
     * target environments — for manual debugging in a terminal.
     * Returns: { source: "curl ...", target: "curl ..." }
     */
    @GetMapping("/case/curl")
    public ResponseEntity<ApiResponse<Map<String, String>>> getCaseCurl(
            @RequestParam String groupName,
            @RequestParam String caseId) {
        if (!session.hasSuite()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("No suite loaded"));
        }
        TestSuite suite = session.getTestSuite();
        TestGroup group = suite.getTestGroups().stream()
                .filter(g -> g.getName().equals(groupName)).findFirst().orElse(null);
        if (group == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Group not found: " + groupName));
        }
        TestRequest tc = group.getTestRequests().stream()
                .filter(c -> c.getId().equals(caseId)).findFirst().orElse(null);
        if (tc == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Case not found: " + caseId));
        }

        ExecutionConfig ec = suite.getSettings().getExecutionConfig();
        Environment sourceEnv = suite.findEnvironment(ec.getSourceEnvironment());
        Environment targetEnv = suite.findEnvironment(ec.getTargetEnvironment());

        AuthProfile sourceAuth = findProfile(suite, sourceEnv != null ? sourceEnv.getAuthProfile() : null);
        AuthProfile targetAuth = findProfile(suite, targetEnv != null ? targetEnv.getAuthProfile() : null);

        Map<String, String> result = new HashMap<>();
        result.put("source", curlBuilder.build(sourceEnv, tc, sourceAuth));
        result.put("target", curlBuilder.build(targetEnv, tc, targetAuth));
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * GET /api/execute/progress — frontend polls this every second.
     */
    @GetMapping("/progress")
    public ResponseEntity<ApiResponse<ExecutionProgress>> getProgress() {
        return ResponseEntity.ok(ApiResponse.ok(session.getProgress()));
    }

    private AuthProfile findProfile(TestSuite suite, String name) {
        if (name == null || suite.getAuthProfiles() == null) return null;
        return suite.getAuthProfiles().stream()
                .filter(p -> name.equals(p.getName())).findFirst().orElse(null);
    }
}
