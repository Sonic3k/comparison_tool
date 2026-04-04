package com.fpt.comparison_tool.controller;

import com.fpt.comparison_tool.dto.ApiResponse;
import com.fpt.comparison_tool.model.ExecutionTask;
import com.fpt.comparison_tool.service.TaskQueueService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskQueueService taskQueue;

    public TaskController(TaskQueueService taskQueue) { this.taskQueue = taskQueue; }

    /** Submit a task — returns 202 immediately, task enters queue. */
    @PostMapping
    public ResponseEntity<ApiResponse<ExecutionTask>> submit(@RequestBody Map<String, Object> body) {
        String suiteId = (String) body.get("suiteId");
        if (suiteId == null || suiteId.isBlank())
            return ResponseEntity.badRequest().body(ApiResponse.error("suiteId is required"));

        @SuppressWarnings("unchecked")
        List<String> groups = (List<String>) body.getOrDefault("groups", List.of());

        try {
            ExecutionTask task = taskQueue.submit(suiteId, groups);
            return ResponseEntity.accepted().body(ApiResponse.ok("Task queued", task));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ExecutionTask>>> listAll() {
        return ResponseEntity.ok(ApiResponse.ok(taskQueue.getAllTasks()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ExecutionTask>> getTask(@PathVariable String id) {
        return taskQueue.getTask(id)
                .map(t -> ResponseEntity.ok(ApiResponse.ok(t)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stats() {
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "pending", taskQueue.getPendingCount(),
                "running", taskQueue.getRunningCount(),
                "maxSlots", 5
        )));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> cancel(@PathVariable String id) {
        boolean ok = taskQueue.cancel(id);
        if (!ok) return ResponseEntity.badRequest()
                .body(ApiResponse.error("Task not found or not cancellable (only PENDING tasks can be cancelled)"));
        return ResponseEntity.ok(ApiResponse.ok("Task cancelled", null));
    }
}
