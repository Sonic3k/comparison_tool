package com.fpt.comparison_tool.controller;

import com.fpt.comparison_tool.dto.ApiResponse;
import com.fpt.comparison_tool.model.GlobalVariable;
import com.fpt.comparison_tool.model.TestSuite;
import com.fpt.comparison_tool.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Session-global variables (Postman-style). Populated automatically by
 * extractVariables during runs; fully editable here so a value can be
 * tweaked by hand before re-running a single request.
 */
@RestController
@RequestMapping("/api/variables")
public class VariableController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SessionService session;

    public VariableController(SessionService session) {
        this.session = session;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<GlobalVariable>>> list() {
        if (!session.hasSuite()) return ResponseEntity.badRequest().body(ApiResponse.error("No suite loaded"));
        return ResponseEntity.ok(ApiResponse.ok(session.getTestSuite().getGlobalVariables()));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<List<GlobalVariable>>> upsert(@RequestBody Map<String, String> body) {
        if (!session.hasSuite()) return ResponseEntity.badRequest().body(ApiResponse.error("No suite loaded"));
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("name is required"));
        }
        TestSuite suite = session.getTestSuite();
        suite.putGlobalVariable(name.trim(), body.getOrDefault("value", ""), LocalDateTime.now().format(TS));
        return ResponseEntity.ok(ApiResponse.ok("Saved", suite.getGlobalVariables()));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<ApiResponse<List<GlobalVariable>>> delete(@PathVariable String name) {
        if (!session.hasSuite()) return ResponseEntity.badRequest().body(ApiResponse.error("No suite loaded"));
        TestSuite suite = session.getTestSuite();
        suite.getGlobalVariables().removeIf(v -> name.equals(v.getName()));
        return ResponseEntity.ok(ApiResponse.ok("Deleted", suite.getGlobalVariables()));
    }

    @DeleteMapping
    public ResponseEntity<ApiResponse<List<GlobalVariable>>> clear() {
        if (!session.hasSuite()) return ResponseEntity.badRequest().body(ApiResponse.error("No suite loaded"));
        session.getTestSuite().getGlobalVariables().clear();
        return ResponseEntity.ok(ApiResponse.ok("Cleared", session.getTestSuite().getGlobalVariables()));
    }
}
