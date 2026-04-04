package com.fpt.comparison_tool.controller;

import com.fpt.comparison_tool.dto.ApiResponse;
import com.fpt.comparison_tool.model.*;
import com.fpt.comparison_tool.service.SuiteRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/suites/{suiteId}/groups/{groupName}/cases")
public class TestCaseController {

    private final SuiteRegistry registry;
    public TestCaseController(SuiteRegistry registry) { this.registry = registry; }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TestCase>>> list(@PathVariable String suiteId, @PathVariable String groupName) {
        return ResponseEntity.ok(ApiResponse.ok(requireGroup(suiteId, groupName).getTestCases()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TestCase>> create(@PathVariable String suiteId,
            @PathVariable String groupName, @RequestBody TestCase tc) {
        TestGroup group = requireGroup(suiteId, groupName);
        if (findCase(group, tc.getId()) != null)
            return ResponseEntity.badRequest().body(ApiResponse.error("TC ID '" + tc.getId() + "' already exists"));
        group.addTestCase(tc);
        return ResponseEntity.ok(ApiResponse.ok("Test case created", tc));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TestCase>> update(@PathVariable String suiteId,
            @PathVariable String groupName, @PathVariable String id, @RequestBody TestCase updated) {
        TestCase tc = requireCase(requireGroup(suiteId, groupName), id);
        tc.setName(updated.getName()); tc.setDescription(updated.getDescription());
        tc.setEnabled(updated.isEnabled()); tc.setVerificationMode(updated.getVerificationMode());
        tc.setMethod(updated.getMethod()); tc.setEndpoint(updated.getEndpoint());
        tc.setQueryParams(updated.getQueryParams()); tc.setFormParams(updated.getFormParams());
        tc.setJsonBody(updated.getJsonBody()); tc.setHeaders(updated.getHeaders());
        tc.setAuthor(updated.getAuthor()); tc.setComparisonConfig(updated.getComparisonConfig());
        tc.setAutomationConfig(updated.getAutomationConfig());
        return ResponseEntity.ok(ApiResponse.ok(tc));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String suiteId,
            @PathVariable String groupName, @PathVariable String id) {
        TestGroup group = requireGroup(suiteId, groupName);
        boolean removed = group.getTestCases().removeIf(tc -> tc.getId().equals(id));
        if (!removed) return ResponseEntity.badRequest().body(ApiResponse.error("TC '" + id + "' not found"));
        return ResponseEntity.ok(ApiResponse.ok("Test case deleted", null));
    }

    @PatchMapping("/{id}/toggle")
    public ResponseEntity<ApiResponse<TestCase>> toggle(@PathVariable String suiteId,
            @PathVariable String groupName, @PathVariable String id) {
        TestCase tc = requireCase(requireGroup(suiteId, groupName), id);
        tc.setEnabled(!tc.isEnabled());
        return ResponseEntity.ok(ApiResponse.ok(tc));
    }

    private TestGroup requireGroup(String suiteId, String groupName) {
        TestSuite suite = registry.get(suiteId)
                .orElseThrow(() -> new IllegalArgumentException("Suite not found: " + suiteId));
        return suite.getTestGroups().stream().filter(g -> g.getName().equals(groupName))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Group '" + groupName + "' not found"));
    }
    private TestCase findCase(TestGroup g, String id) {
        return g.getTestCases().stream().filter(tc -> tc.getId().equals(id)).findFirst().orElse(null);
    }
    private TestCase requireCase(TestGroup g, String id) {
        TestCase tc = findCase(g, id);
        if (tc == null) throw new IllegalArgumentException("TC '" + id + "' not found");
        return tc;
    }
}
