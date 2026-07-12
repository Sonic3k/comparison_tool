package com.fpt.comparison_tool.controller;

import com.fpt.comparison_tool.dto.ApiResponse;
import com.fpt.comparison_tool.model.TestRequest;
import com.fpt.comparison_tool.model.TestGroup;
import com.fpt.comparison_tool.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/groups/{groupName}/cases")
public class TestRequestController {

    private final SessionService session;

    public TestRequestController(SessionService session) {
        this.session = session;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TestRequest>>> listCases(
            @PathVariable("groupName") String groupName) {
        return ResponseEntity.ok(ApiResponse.ok(requireGroup(groupName).getTestRequests()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TestRequest>> createCase(
            @PathVariable("groupName") String groupName, @RequestBody TestRequest testRequest) {
        TestGroup group = requireGroup(groupName);
        if (findCase(group, testRequest.getId()) != null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Test case ID '" + testRequest.getId() + "' already exists"));
        }
        group.addTestRequest(testRequest);
        group.normalize();
        return ResponseEntity.ok(ApiResponse.ok("Test case created", testRequest));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TestRequest>> updateCase(
            @PathVariable("groupName") String groupName,
            @PathVariable("id") String id,
            @RequestBody TestRequest updated) {
        TestGroup group = requireGroup(groupName);
        TestRequest existing = requireCase(group, id);

        // Replace all definition fields but keep result intact
        existing.setName(updated.getName());
        existing.setDescription(updated.getDescription());
        existing.setEnabled(updated.isEnabled());
        existing.setVerificationMode(updated.getVerificationMode());
        existing.setPhase(updated.getPhase());
        existing.setMethod(updated.getMethod());
        existing.setEndpoint(updated.getEndpoint());
        existing.setQueryParams(updated.getQueryParams());
        existing.setFormParams(updated.getFormParams());
        existing.setJsonBody(updated.getJsonBody());
        existing.setHeaders(updated.getHeaders());
        existing.setAuthor(updated.getAuthor());
        existing.setExtractVariables(updated.getExtractVariables());
        existing.setComparisonConfig(updated.getComparisonConfig());
        existing.setAutomationConfig(updated.getAutomationConfig());
        if (updated.getTestCaseId() != null && !updated.getTestCaseId().isBlank()) {
            existing.setTestCaseId(updated.getTestCaseId());
        }
        group.normalize();

        return ResponseEntity.ok(ApiResponse.ok(existing));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteCase(
            @PathVariable("groupName") String groupName, @PathVariable("id") String id) {
        TestGroup group = requireGroup(groupName);
        boolean removed = group.getTestRequests().removeIf(tc -> tc.getId().equals(id));
        if (!removed) return ResponseEntity.badRequest()
                .body(ApiResponse.error("Test case '" + id + "' not found"));
        return ResponseEntity.ok(ApiResponse.ok("Test case deleted", null));
    }


    @PatchMapping("/{caseId}/toggle")
    public ResponseEntity<ApiResponse<TestRequest>> toggleCase(
            @PathVariable("groupName") String groupName, @PathVariable("caseId") String caseId) {
        TestGroup group = requireGroup(groupName);
        TestRequest tc = requireCase(group, caseId);
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

    private TestRequest findCase(TestGroup group, String id) {
        return group.getTestRequests().stream()
                .filter(tc -> tc.getId().equals(id))
                .findFirst().orElse(null);
    }

    private TestRequest requireCase(TestGroup group, String id) {
        TestRequest tc = findCase(group, id);
        if (tc == null) throw new IllegalArgumentException("Test case '" + id + "' not found");
        return tc;
    }
}