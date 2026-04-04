package com.fpt.comparison_tool.controller;

import com.fpt.comparison_tool.dto.ApiResponse;
import com.fpt.comparison_tool.model.TestCase;
import com.fpt.comparison_tool.model.TestGroup;
import com.fpt.comparison_tool.model.TestSuite;
import com.fpt.comparison_tool.service.SuiteRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/suites/{suiteId}/groups/{groupName}/cases")
public class TestCaseController {

    private final SuiteRegistry registry;

    public TestCaseController(SuiteRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TestCase>>> listCases(
            @PathVariable String suiteId, @PathVariable String groupName) {
        return ResponseEntity.ok(ApiResponse.ok(requireGroup(suiteId, groupName).getTestCases()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TestCase>> createCase(
            @PathVariable String suiteId, @PathVariable String groupName,
            @RequestBody TestCase testCase) {
        TestGroup group = requireGroup(suiteId, groupName);
        if (findCase(group, testCase.getId()) != null)
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Test case ID '" + testCase.getId() + "' already exists"));
        group.addTestCase(testCase);
        return ResponseEntity.ok(ApiResponse.ok("Test case created", testCase));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TestCase>> updateCase(
            @PathVariable String suiteId, @PathVariable String groupName,
            @PathVariable String id, @RequestBody TestCase updated) {
        TestCase existing = requireCase(requireGroup(suiteId, groupName), id);
        existing.setName(updated.getName());
        existing.setDescription(updated.getDescription());
        existing.setEnabled(updated.isEnabled());
        existing.setVerificationMode(updated.getVerificationMode());
        existing.setMethod(updated.getMethod());
        existing.setEndpoint(updated.getEndpoint());
        existing.setQueryParams(updated.getQueryParams());
        existing.setFormParams(updated.getFormParams());
        existing.setJsonBody(updated.getJsonBody());
        existing.setHeaders(updated.getHeaders());
        existing.setAuthor(updated.getAuthor());
        existing.setComparisonConfig(updated.getComparisonConfig());
        existing.setAutomationConfig(updated.getAutomationConfig());
        return ResponseEntity.ok(ApiResponse.ok(existing));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteCase(
            @PathVariable String suiteId, @PathVariable String groupName,
            @PathVariable String id) {
        TestGroup group = requireGroup(suiteId, groupName);
        boolean removed = group.getTestCases().removeIf(tc -> tc.getId().equals(id));
        if (!removed) return ResponseEntity.badRequest()
                .body(ApiResponse.error("Test case '" + id + "' not found"));
        return ResponseEntity.ok(ApiResponse.ok("Test case deleted", null));
    }

    @PatchMapping("/{caseId}/toggle")
    public ResponseEntity<ApiResponse<TestCase>> toggleCase(
            @PathVariable String suiteId, @PathVariable String groupName,
            @PathVariable String caseId) {
        TestCase tc = requireCase(requireGroup(suiteId, groupName), caseId);
        tc.setEnabled(!tc.isEnabled());
        return ResponseEntity.ok(ApiResponse.ok(tc));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private TestGroup requireGroup(String suiteId, String groupName) {
        TestSuite suite = registry.get(suiteId)
                .orElseThrow(() -> new IllegalArgumentException("Suite not found: " + suiteId));
        return suite.getTestGroups().stream()
                .filter(g -> g.getName().equals(groupName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Group '" + groupName + "' not found"));
    }

    private TestCase findCase(TestGroup group, String id) {
        return group.getTestCases().stream()
                .filter(tc -> tc.getId().equals(id)).findFirst().orElse(null);
    }

    private TestCase requireCase(TestGroup group, String id) {
        TestCase tc = findCase(group, id);
        if (tc == null) throw new IllegalArgumentException("Test case '" + id + "' not found");
        return tc;
    }
}
