package com.fpt.comparison_tool.controller;

import com.fpt.comparison_tool.dto.ApiResponse;
import com.fpt.comparison_tool.generator.XmlGenerator;
import com.fpt.comparison_tool.model.TestGroup;
import com.fpt.comparison_tool.model.TestSuite;
import com.fpt.comparison_tool.service.SuiteRegistry;
import com.fpt.comparison_tool.service.XmlImportService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.List;

@RestController
@RequestMapping("/api/suites/{suiteId}/groups")
public class GroupController {

    private final SuiteRegistry    registry;
    private final XmlImportService xmlImport    = new XmlImportService();
    private final XmlGenerator     xmlGenerator = new XmlGenerator();

    public GroupController(SuiteRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TestGroup>>> listGroups(@PathVariable String suiteId) {
        return ResponseEntity.ok(ApiResponse.ok(requireSuite(suiteId).getTestGroups()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TestGroup>> createGroup(
            @PathVariable String suiteId, @RequestBody TestGroup group) {
        TestSuite suite = requireSuite(suiteId);
        if (findGroup(suite, group.getName()) != null)
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Group '" + group.getName() + "' already exists"));
        suite.addTestGroup(group);
        return ResponseEntity.ok(ApiResponse.ok("Group created", group));
    }

    @PutMapping("/{name}")
    public ResponseEntity<ApiResponse<TestGroup>> updateGroup(
            @PathVariable String suiteId, @PathVariable String name,
            @RequestBody TestGroup updated) {
        TestGroup g = requireGroup(requireSuite(suiteId), name);
        g.setName(updated.getName());
        g.setDescription(updated.getDescription());
        g.setOwner(updated.getOwner());
        return ResponseEntity.ok(ApiResponse.ok(g));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<ApiResponse<Void>> deleteGroup(
            @PathVariable String suiteId, @PathVariable String name) {
        TestSuite suite = requireSuite(suiteId);
        boolean removed = suite.getTestGroups().removeIf(g -> g.getName().equals(name));
        if (!removed) return ResponseEntity.badRequest()
                .body(ApiResponse.error("Group '" + name + "' not found"));
        return ResponseEntity.ok(ApiResponse.ok("Group deleted", null));
    }

    @PatchMapping("/{name}/toggle")
    public ResponseEntity<ApiResponse<TestGroup>> toggleGroup(
            @PathVariable String suiteId, @PathVariable String name) {
        TestGroup g = requireGroup(requireSuite(suiteId), name);
        g.setEnabled(!g.isEnabled());
        return ResponseEntity.ok(ApiResponse.ok(g));
    }

    @GetMapping("/{name}/export/xml")
    public ResponseEntity<byte[]> exportGroupXml(
            @PathVariable String suiteId, @PathVariable String name) throws Exception {
        TestGroup group = requireGroup(requireSuite(suiteId), name);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        xmlGenerator.generateGroup(group, out);
        String filename = "TestGroup_" + name.replaceAll("[^a-zA-Z0-9_-]", "_") + ".xml";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_XML)
                .body(out.toByteArray());
    }

    @PostMapping("/import/xml")
    public ResponseEntity<ApiResponse<TestGroup>> importGroupXml(
            @PathVariable String suiteId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mode", defaultValue = "new") String mode) {
        TestSuite suite = requireSuite(suiteId);
        try {
            TestGroup imported = xmlImport.importGroup(file.getInputStream());
            if (imported.getName() == null || imported.getName().isBlank())
                return ResponseEntity.badRequest().body(ApiResponse.error("Group name missing in XML"));

            TestGroup existing = findGroup(suite, imported.getName());
            if (existing != null) {
                if ("replace".equalsIgnoreCase(mode)) {
                    List<TestGroup> groups = suite.getTestGroups();
                    groups.set(groups.indexOf(existing), imported);
                    return ResponseEntity.ok(ApiResponse.ok("Group replaced: " + imported.getName(), imported));
                } else {
                    return ResponseEntity.badRequest()
                            .body(ApiResponse.error("Group '" + imported.getName() + "' exists. Use mode=replace."));
                }
            }
            suite.addTestGroup(imported);
            return ResponseEntity.ok(ApiResponse.ok("Group imported: " + imported.getName(), imported));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed: " + e.getMessage()));
        }
    }

    @PostMapping("/import/postman")
    public ResponseEntity<ApiResponse<Void>> importPostman(@PathVariable String suiteId,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(ApiResponse.error("Postman import not yet implemented"));
    }

    @PostMapping("/import/har")
    public ResponseEntity<ApiResponse<Void>> importHar(@PathVariable String suiteId,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(ApiResponse.error("HAR import not yet implemented"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private TestSuite requireSuite(String suiteId) {
        return registry.get(suiteId)
                .orElseThrow(() -> new IllegalArgumentException("Suite not found: " + suiteId));
    }

    private TestGroup findGroup(TestSuite suite, String name) {
        return suite.getTestGroups().stream()
                .filter(g -> g.getName().equals(name)).findFirst().orElse(null);
    }

    private TestGroup requireGroup(TestSuite suite, String name) {
        TestGroup g = findGroup(suite, name);
        if (g == null) throw new IllegalArgumentException("Group '" + name + "' not found");
        return g;
    }
}
