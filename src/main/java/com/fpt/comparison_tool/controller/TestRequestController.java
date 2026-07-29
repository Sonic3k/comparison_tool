package com.fpt.comparison_tool.controller;

import com.fpt.comparison_tool.dto.ApiResponse;
import com.fpt.comparison_tool.model.TestRequest;
import com.fpt.comparison_tool.model.TestCaseDef;
import com.fpt.comparison_tool.model.TestGroup;
import com.fpt.comparison_tool.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * PUT /api/groups/{groupName}/cases/reorder
     * Body: { "requestIds": ["R1", "R2", ...] }
     *
     * One endpoint covers both "move a test case" and "move a request inside a
     * test case", because a group has a single flat ordered list of requests —
     * test cases are chunks derived from it by testCaseId, not a list of their
     * own. The caller sends the whole new flat order.
     *
     * The result is canonicalised so requests sharing a testCaseId end up
     * contiguous (first-appearance order wins). Without this a test case could
     * be split across the list, and the UI — which chunks by testCaseId — would
     * then display an order the executor does not follow.
     */
    @PutMapping("/reorder")
    public ResponseEntity<ApiResponse<List<TestRequest>>> reorderCases(
            @PathVariable("groupName") String groupName,
            @RequestBody Map<String, List<String>> body) {
        TestGroup group = requireGroup(groupName);
        List<String> ids = body != null ? body.get("requestIds") : null;
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("requestIds is required"));
        }

        List<TestRequest> current = group.getTestRequests();
        if (ids.size() != current.size() || new HashSet<>(ids).size() != ids.size()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("requestIds must be a permutation of the group's requests"));
        }

        List<TestRequest> reordered = new ArrayList<>(current.size());
        for (String id : ids) {
            TestRequest r = findCase(group, id);
            if (r == null) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Request '" + id + "' not found"));
            }
            reordered.add(r);
        }

        // Canonicalise: keep members of the same test case together.
        Map<String, List<TestRequest>> chunks = new LinkedHashMap<>();
        for (TestRequest r : reordered) {
            String tcId = r.getTestCaseId() != null ? r.getTestCaseId() : r.getId();
            chunks.computeIfAbsent(tcId, k -> new ArrayList<>()).add(r);
        }
        List<TestRequest> canonical = new ArrayList<>(reordered.size());
        chunks.values().forEach(canonical::addAll);
        group.setTestRequests(canonical);

        // Mirror the order in the def registry so XML/Excel read top-to-bottom.
        List<TestCaseDef> defs = group.getTestCaseDefs();
        if (defs != null && !defs.isEmpty()) {
            List<TestCaseDef> sorted = new ArrayList<>(defs.size());
            for (String tcId : chunks.keySet()) {
                TestCaseDef d = group.findTestCaseDef(tcId);
                if (d != null) sorted.add(d);
            }
            for (TestCaseDef d : defs) if (!sorted.contains(d)) sorted.add(d); // defs with no members
            group.setTestCaseDefs(sorted);
        }

        group.normalize();
        return ResponseEntity.ok(ApiResponse.ok("Order updated", canonical));
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