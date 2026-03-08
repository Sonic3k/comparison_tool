package com.fpt.comparison_tool.controller;

import com.fpt.comparison_tool.dto.ApiResponse;
import com.fpt.comparison_tool.model.TestCase;
import com.fpt.comparison_tool.model.TestGroup;
import com.fpt.comparison_tool.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/groups/{groupName}/cases")
public class TestCaseController {

    private final SessionService session;

    public TestCaseController(SessionService session) {
        this.session = session;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TestCase>>> listCases(
            @PathVariable("groupName") String groupName) {
        return ResponseEntity.ok(ApiResponse.ok(requireGroup(groupName).getTestCases()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TestCase>> createCase(
            @PathVariable("groupName") String groupName, @RequestBody TestCase testCase) {
        TestGroup group = requireGroup(groupName);
        if (findCase(group, testCase.getId()) != null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Test case ID '" + testCase.getId() + "' already exists"));
        }
        group.addTestCase(testCase);
        return ResponseEntity.ok(ApiResponse.ok("Test case created", testCase));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TestCase>> updateCase(
            @PathVariable("groupName") String groupName,
            @PathVariable("id") String id,
            @RequestBody TestCase updated) {
        TestGroup group = requireGroup(groupName);
        TestCase existing = requireCase(group, id);

        // Replace all definition fields but keep result intact
        existing.setName(updated.getName());
        existing.setDescription(updated.getDescription());
        existing.setEnabled(updated.isEnabled());
        existing.setMethod(updated.getMethod());
        existing.setEndpoint(updated.getEndpoint());
        existing.setQueryParams(updated.getQueryParams());
        existing.setFormParams(updated.getFormParams());
        existing.setJsonBody(updated.getJsonBody());
        existing.setHeaders(updated.getHeaders());
        existing.setAuthor(updated.getAuthor());
        existing.setComparisonConfig(updated.getComparisonConfig());

        return ResponseEntity.ok(ApiResponse.ok(existing));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteCase(
            @PathVariable("groupName") String groupName, @PathVariable("id") String id) {
        TestGroup group = requireGroup(groupName);
        boolean removed = group.getTestCases().removeIf(tc -> tc.getId().equals(id));
        if (!removed) return ResponseEntity.badRequest()
                .body(ApiResponse.error("Test case '" + id + "' not found"));
        return ResponseEntity.ok(ApiResponse.ok("Test case deleted", null));
    }


    @PatchMapping("/{caseId}/toggle")
    public ResponseEntity<ApiResponse<TestCase>> toggleCase(
            @PathVariable("groupName") String groupName, @PathVariable("caseId") String caseId) {
        TestGroup group = requireGroup(groupName);
        TestCase tc = requireCase(group, caseId);
        tc.setEnabled(!tc.isEnabled());
        return ResponseEntity.ok(ApiResponse.ok(tc));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private TestGroup requireGroup(String name) {
        return session.getTestSuite().getTestGroups().stream()
                .filter(g -> g.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Group '" + name + "' not found"));
    }

    private TestCase findCase(TestGroup group, String id) {
        return group.getTestCases().stream()
                .filter(tc -> tc.getId().equals(id))
                .findFirst().orElse(null);
    }

    private TestCase requireCase(TestGroup group, String id) {
        TestCase tc = findCase(group, id);
        if (tc == null) throw new IllegalArgumentException("Test case '" + id + "' not found");
        return tc;
    }
}