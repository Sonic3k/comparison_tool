package com.fpt.comparison_tool.controller;

import com.fpt.comparison_tool.dto.ApiResponse;
import com.fpt.comparison_tool.model.TestCaseDef;
import com.fpt.comparison_tool.model.TestGroup;
import com.fpt.comparison_tool.model.TestRequest;
import com.fpt.comparison_tool.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * CRUD for logical Test Cases (TestCaseDef) inside a group, plus assignment
 * of requests to a test case. Requests themselves live under
 * /api/groups/{groupName}/cases (TestRequestController).
 */
@RestController
@RequestMapping("/api/groups/{groupName}/testcases")
public class TestCaseDefController {

    private final SessionService session;

    public TestCaseDefController(SessionService session) {
        this.session = session;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TestCaseDef>>> listDefs(
            @PathVariable("groupName") String groupName) {
        TestGroup group = requireGroup(groupName);
        group.normalize();
        return ResponseEntity.ok(ApiResponse.ok(group.getTestCaseDefs()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TestCaseDef>> createDef(
            @PathVariable("groupName") String groupName, @RequestBody TestCaseDef def) {
        TestGroup group = requireGroup(groupName);
        if (def.getId() == null || def.getId().isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Test case id is required"));
        }
        if (group.findTestCaseDef(def.getId()) != null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Test case '" + def.getId() + "' already exists"));
        }
        if (def.getName() == null || def.getName().isBlank()) def.setName(def.getId());
        group.getTestCaseDefs().add(def);
        return ResponseEntity.ok(ApiResponse.ok("Test case created", def));
    }

    /**
     * PUT /api/groups/{groupName}/testcases/{id}
     * Body: { "id": "NEW_ID"?, "name": "...", "description": "..." }
     *
     * Renaming the id cascades to every member request's testCaseId.
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TestCaseDef>> updateDef(
            @PathVariable("groupName") String groupName,
            @PathVariable("id") String id,
            @RequestBody TestCaseDef updated) {
        TestGroup group = requireGroup(groupName);
        TestCaseDef existing = group.findTestCaseDef(id);
        if (existing == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Test case '" + id + "' not found"));
        }

        String newId = updated.getId();
        if (newId != null && !newId.isBlank() && !newId.equals(id)) {
            if (group.findTestCaseDef(newId) != null) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Test case '" + newId + "' already exists"));
            }
            existing.setId(newId);
            for (TestRequest r : group.getTestRequests()) {
                if (id.equals(r.getTestCaseId())) r.setTestCaseId(newId);
            }
        }
        if (updated.getName() != null)        existing.setName(updated.getName());
        if (updated.getDescription() != null) existing.setDescription(updated.getDescription());
        return ResponseEntity.ok(ApiResponse.ok(existing));
    }

    /**
     * DELETE — removes the def; member requests fall back to
     * 1 request : 1 test case (testCaseId = their own id).
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteDef(
            @PathVariable("groupName") String groupName, @PathVariable("id") String id) {
        TestGroup group = requireGroup(groupName);
        boolean removed = group.getTestCaseDefs().removeIf(d -> id.equals(d.getId()));
        if (!removed) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Test case '" + id + "' not found"));
        }
        for (TestRequest r : group.getTestRequests()) {
            if (id.equals(r.getTestCaseId())) r.setTestCaseId(r.getId());
        }
        group.normalize();
        return ResponseEntity.ok(ApiResponse.ok("Test case deleted — member requests reverted to 1:1", null));
    }

    /**
     * POST /api/groups/{groupName}/testcases/{id}/assign
     * Body: { "requestIds": ["REQ1", "REQ2"] }
     *
     * Moves the listed requests into this test case. The def is auto-created
     * if it does not exist yet. Defs that end up without members are kept
     * (they may receive requests later).
     */
    @PostMapping("/{id}/assign")
    public ResponseEntity<ApiResponse<TestCaseDef>> assignRequests(
            @PathVariable("groupName") String groupName,
            @PathVariable("id") String id,
            @RequestBody Map<String, List<String>> body) {
        TestGroup group = requireGroup(groupName);
        List<String> requestIds = body != null ? body.get("requestIds") : null;
        if (requestIds == null || requestIds.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("requestIds is required"));
        }

        TestCaseDef def = group.findTestCaseDef(id);
        if (def == null) {
            def = new TestCaseDef(id, id, null);
            group.getTestCaseDefs().add(def);
        }

        int moved = 0;
        for (String reqId : requestIds) {
            TestRequest r = group.findTestRequest(reqId);
            if (r != null) {
                r.setTestCaseId(id);
                moved++;
            }
        }
        if (moved == 0) {
            return ResponseEntity.badRequest().body(ApiResponse.error("No matching requests found"));
        }
        return ResponseEntity.ok(ApiResponse.ok(moved + " request(s) assigned to test case '" + id + "'", def));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private TestGroup requireGroup(String name) {
        return session.getTestSuite().getTestGroups().stream()
                .filter(g -> g.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Group '" + name + "' not found"));
    }
}
