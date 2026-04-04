package com.fpt.comparison_tool.controller;

import com.fpt.comparison_tool.dto.ApiResponse;
import com.fpt.comparison_tool.model.ExecutionTask;
import com.fpt.comparison_tool.model.VerificationMode;
import com.fpt.comparison_tool.service.TaskQueueService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Task lifecycle management.
 * POST   /api/tasks          — submit task {suiteId, groups[], verificationMode}
 * GET    /api/tasks          — list all tasks
 * GET    /api/tasks/active   — only PENDING + IN_PROGRESS
 * GET    /api/tasks/{id}     — single task detail (for polling)
 * DELETE /api/tasks/{id}     — cancel PENDING task
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskQueueService taskQueue;

    public TaskController(TaskQueueService taskQueue) {
        this.taskQueue = taskQueue;
    }

    /** Submit a new execution task — returns 202 Accepted immediately. */
    @PostMapping
    public ResponseEntity<ApiResponse<ExecutionTask>> submit(
            @RequestBody Map<String, Object> body) {
        String suiteId = (String) body.get("suiteId");
        if (suiteId == null || suiteId.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("suiteId is required"));
        }

        @SuppressWarnings("unchecked")
        List<String> groups = (List<String>) body.getOrDefault("groups", List.of());

        String vmStr = (String) body.get("verificationMode");
        VerificationMode vm = vmStr != null && !vmStr.isBlank()
                ? VerificationMode.from(vmStr) : null;

        try {
            ExecutionTask task = taskQueue.submit(suiteId, groups, vm);
            return ResponseEntity.accepted().body(ApiResponse.ok("Task queued", task));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ExecutionTask>>> listAll() {
        List<ExecutionTask> tasks = taskQueue.getAllTasks();
        return ResponseEntity.ok(ApiResponse.ok(tasks.reversed()));
    }

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<ExecutionTask>>> listActive() {
        return ResponseEntity.ok(ApiResponse.ok(taskQueue.getActiveTasks()));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stats() {
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "pending",  taskQueue.getPendingCount(),
                "running",  taskQueue.getActiveSlots(),
                "maxSlots", 5
        )));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ExecutionTask>> getTask(@PathVariable String id) {
        return taskQueue.getTask(id)
                .map(t -> ResponseEntity.ok(ApiResponse.ok(t)))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> cancel(@PathVariable String id) {
        boolean ok = taskQueue.cancel(id);
        if (!ok) return ResponseEntity.badRequest()
                .body(ApiResponse.error("Task not found or cannot be cancelled (only PENDING tasks can be cancelled)"));
        return ResponseEntity.ok(ApiResponse.ok("Task cancelled", null));
    }
}
