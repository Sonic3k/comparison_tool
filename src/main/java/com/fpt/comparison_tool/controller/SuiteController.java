package com.fpt.comparison_tool.controller;

import com.fpt.comparison_tool.dto.ApiResponse;
import com.fpt.comparison_tool.model.*;
import com.fpt.comparison_tool.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Suite-level settings/environment/auth mutations.
 * Suite import/list/delete is in MultiSuiteController.
 */
@RestController
@RequestMapping("/api/suites/{suiteId}")
public class SuiteController {

    private final SuiteRegistry registry;
    private final AuthService   authService;

    public SuiteController(SuiteRegistry registry, AuthService authService) {
        this.registry    = registry;
        this.authService = authService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<TestSuite>> getSuite(@PathVariable String suiteId) {
        return registry.get(suiteId)
                .map(s -> ResponseEntity.ok(ApiResponse.ok(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/settings")
    public ResponseEntity<ApiResponse<SuiteSettings>> updateSettings(
            @PathVariable String suiteId, @RequestBody SuiteSettings settings) {
        TestSuite suite = require(suiteId);
        suite.setSettings(settings);
        return ResponseEntity.ok(ApiResponse.ok(settings));
    }

    @PutMapping("/environment")
    public ResponseEntity<ApiResponse<List<Environment>>> updateEnvironments(
            @PathVariable String suiteId, @RequestBody List<Environment> envs) {
        TestSuite suite = require(suiteId);
        suite.setEnvironments(envs);
        return ResponseEntity.ok(ApiResponse.ok(envs));
    }

    @PutMapping("/auth-profiles")
    public ResponseEntity<ApiResponse<List<AuthProfile>>> updateAuthProfiles(
            @PathVariable String suiteId, @RequestBody List<AuthProfile> profiles) {
        TestSuite suite = require(suiteId);
        suite.setAuthProfiles(profiles);
        authService.clearCache();
        return ResponseEntity.ok(ApiResponse.ok(profiles));
    }

    private TestSuite require(String suiteId) {
        return registry.get(suiteId)
                .orElseThrow(() -> new IllegalArgumentException("Suite not found: " + suiteId));
    }
}
