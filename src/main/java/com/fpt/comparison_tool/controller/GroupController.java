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

    public GroupController(SuiteRegistry registry) { this.registry = registry; }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TestGroup>>> list(@PathVariable String suiteId) {
        return ResponseEntity.ok(ApiResponse.ok(require(suiteId).getTestGroups()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TestGroup>> create(@PathVariable String suiteId, @RequestBody TestGroup group) {
        TestSuite suite = require(suiteId);
        if (findGroup(suite, group.getName()) != null)
            return ResponseEntity.badRequest().body(ApiResponse.error("Group '" + group.getName() + "' already exists"));
        suite.addTestGroup(group);
        return ResponseEntity.ok(ApiResponse.ok("Group created", group));
    }

    @PutMapping("/{name}")
    public ResponseEntity<ApiResponse<TestGroup>> update(@PathVariable String suiteId,
            @PathVariable String name, @RequestBody TestGroup updated) {
        TestGroup g = requireGroup(require(suiteId), name);
        g.setName(updated.getName()); g.setDescription(updated.getDescription()); g.setOwner(updated.getOwner());
        return ResponseEntity.ok(ApiResponse.ok(g));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String suiteId, @PathVariable String name) {
        TestSuite suite = require(suiteId);
        boolean removed = suite.getTestGroups().removeIf(g -> g.getName().equals(name));
        if (!removed) return ResponseEntity.badRequest().body(ApiResponse.error("Group '" + name + "' not found"));
        return ResponseEntity.ok(ApiResponse.ok("Group deleted", null));
    }

    @GetMapping("/{name}/export/xml")
    public ResponseEntity<byte[]> exportXml(@PathVariable String suiteId, @PathVariable String name) throws Exception {
        TestGroup group = requireGroup(require(suiteId), name);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        xmlGenerator.generateGroup(group, out);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name.replaceAll("[^a-zA-Z0-9_-]","_") + ".xml\"")
                .contentType(MediaType.APPLICATION_XML).body(out.toByteArray());
    }

    @PostMapping("/import/xml")
    public ResponseEntity<ApiResponse<TestGroup>> importXml(@PathVariable String suiteId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value="mode", defaultValue="new") String mode) {
        TestSuite suite = require(suiteId);
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
                }
                return ResponseEntity.badRequest().body(ApiResponse.error("Group exists. Use mode=replace."));
            }
            suite.addTestGroup(imported);
            return ResponseEntity.ok(ApiResponse.ok("Group imported: " + imported.getName(), imported));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed: " + e.getMessage()));
        }
    }

    private TestSuite require(String id) {
        return registry.get(id).orElseThrow(() -> new IllegalArgumentException("Suite not found: " + id));
    }
    private TestGroup findGroup(TestSuite s, String name) {
        return s.getTestGroups().stream().filter(g -> g.getName().equals(name)).findFirst().orElse(null);
    }
    private TestGroup requireGroup(TestSuite s, String name) {
        TestGroup g = findGroup(s, name);
        if (g == null) throw new IllegalArgumentException("Group '" + name + "' not found");
        return g;
    }
}
