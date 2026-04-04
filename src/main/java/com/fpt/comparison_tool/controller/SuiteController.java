package com.fpt.comparison_tool.controller;

import com.fpt.comparison_tool.dto.ApiResponse;
import com.fpt.comparison_tool.model.*;
import com.fpt.comparison_tool.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/suites/{suiteId}")
public class SuiteController {

    private final SuiteRegistry registry;
    private final AuthService   authService;

    public SuiteController(SuiteRegistry registry, AuthService authService) {
        this.registry = registry; this.authService = authService;
    }

    @PutMapping("/settings")
    public ResponseEntity<ApiResponse<SuiteSettings>> updateSettings(
            @PathVariable String suiteId, @RequestBody SuiteSettings settings) {
        require(suiteId).setSettings(settings);
        return ResponseEntity.ok(ApiResponse.ok(settings));
    }

    @PutMapping("/environment")
    public ResponseEntity<ApiResponse<List<Environment>>> updateEnvironment(
            @PathVariable String suiteId, @RequestBody List<Environment> envs) {
        require(suiteId).setEnvironments(envs);
        return ResponseEntity.ok(ApiResponse.ok(envs));
    }

    @PutMapping("/auth-profiles")
    public ResponseEntity<ApiResponse<List<AuthProfile>>> updateAuthProfiles(
            @PathVariable String suiteId, @RequestBody List<AuthProfile> profiles) {
        require(suiteId).setAuthProfiles(profiles);
        authService.clearCache();
        return ResponseEntity.ok(ApiResponse.ok(profiles));
    }

    private TestSuite require(String id) {
        return registry.get(id).orElseThrow(() -> new IllegalArgumentException("Suite not found: " + id));
    }
}
