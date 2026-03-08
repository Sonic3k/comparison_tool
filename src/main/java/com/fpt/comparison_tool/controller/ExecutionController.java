package com.fpt.comparison_tool.controller;

import com.fpt.comparison_tool.dto.ApiResponse;
import com.fpt.comparison_tool.dto.ExecutionProgress;
import com.fpt.comparison_tool.service.ExecutionService;
import com.fpt.comparison_tool.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/execute")
public class ExecutionController {

    private final SessionService session;
    private final ExecutionService executionService;

    public ExecutionController(SessionService session, ExecutionService executionService) {
        this.session          = session;
        this.executionService = executionService;
    }

    /**
     * POST /api/execute
     * Body: { "groups": ["User APIs", "Auth APIs"] }  — empty array = run all
     */
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> startExecution(
            @RequestBody(required = false) Map<String, List<String>> body) {
        if (!session.hasSuite()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("No suite loaded"));
        }
        ExecutionProgress progress = session.getProgress();
        if (progress.isRunning()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Execution already running"));
        }
        List<String> groupFilter = body != null ? body.get("groups") : null;
        executionService.startAsync(session.getTestSuite(), groupFilter, progress);
        return ResponseEntity.ok(ApiResponse.ok("Execution started", null));
    }

    /**
     * GET /api/execute/progress
     * Frontend polls this every second.
     */
    @GetMapping("/progress")
    public ResponseEntity<ApiResponse<ExecutionProgress>> getProgress() {
        return ResponseEntity.ok(ApiResponse.ok(session.getProgress()));
    }
}