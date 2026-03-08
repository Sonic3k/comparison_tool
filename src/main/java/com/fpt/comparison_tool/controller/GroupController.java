package com.fpt.comparison_tool.controller;

import com.fpt.comparison_tool.dto.ApiResponse;
import com.fpt.comparison_tool.generator.XmlGenerator;
import com.fpt.comparison_tool.model.TestGroup;
import com.fpt.comparison_tool.service.SessionService;
import com.fpt.comparison_tool.service.XmlImportService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.List;

@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private final SessionService   session;
    private final XmlImportService xmlImport   = new XmlImportService();
    private final XmlGenerator     xmlGenerator = new XmlGenerator();

    public GroupController(SessionService session) {
        this.session = session;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TestGroup>>> listGroups() {
        return ResponseEntity.ok(ApiResponse.ok(session.getTestSuite().getTestGroups()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TestGroup>> createGroup(@RequestBody TestGroup group) {
        requireSuite();
        if (findGroup(group.getName()) != null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Group '" + group.getName() + "' already exists"));
        }
        session.getTestSuite().addTestGroup(group);
        return ResponseEntity.ok(ApiResponse.ok("Group created", group));
    }

    @PutMapping("/{name}")
    public ResponseEntity<ApiResponse<TestGroup>> updateGroup(
            @PathVariable("name") String name, @RequestBody TestGroup updated) {
        requireSuite();
        TestGroup existing = requireGroup(name);
        existing.setName(updated.getName());
        existing.setDescription(updated.getDescription());
        existing.setOwner(updated.getOwner());
        return ResponseEntity.ok(ApiResponse.ok(existing));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<ApiResponse<Void>> deleteGroup(@PathVariable("name") String name) {
        requireSuite();
        boolean removed = session.getTestSuite().getTestGroups()
                .removeIf(g -> g.getName().equals(name));
        if (!removed) return ResponseEntity.badRequest()
                .body(ApiResponse.error("Group '" + name + "' not found"));
        return ResponseEntity.ok(ApiResponse.ok("Group deleted", null));
    }

    @PatchMapping("/{name}/toggle")
    public ResponseEntity<ApiResponse<TestGroup>> toggleGroup(@PathVariable("name") String name) {
        requireSuite();
        TestGroup g = requireGroup(name);
        g.setEnabled(!g.isEnabled());
        return ResponseEntity.ok(ApiResponse.ok(g));
    }

    // ─── Export single group as XML ───────────────────────────────────────────

    @GetMapping("/{name}/export/xml")
    public ResponseEntity<byte[]> exportGroupXml(@PathVariable("name") String name) throws Exception {
        requireSuite();
        TestGroup group = requireGroup(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        xmlGenerator.generateGroup(group, out);
        String filename = "TestGroup_" + name.replaceAll("[^a-zA-Z0-9_-]", "_") + ".xml";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_XML)
                .body(out.toByteArray());
    }

    // ─── Import single group from XML ─────────────────────────────────────────

    /**
     * mode=new     → error if group name already exists
     * mode=replace → overwrite existing group with same name
     */
    @PostMapping("/import/xml")
    public ResponseEntity<ApiResponse<TestGroup>> importGroupXml(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mode", defaultValue = "new") String mode) {
        requireSuite();
        try {
            TestGroup imported = xmlImport.importGroup(file.getInputStream());
            if (imported.getName() == null || imported.getName().isBlank()) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Group name is missing in XML"));
            }

            TestGroup existing = findGroup(imported.getName());
            if (existing != null) {
                if ("replace".equalsIgnoreCase(mode)) {
                    List<TestGroup> groups = session.getTestSuite().getTestGroups();
                    groups.set(groups.indexOf(existing), imported);
                    return ResponseEntity.ok(ApiResponse.ok("Group replaced: " + imported.getName(), imported));
                } else {
                    return ResponseEntity.badRequest()
                            .body(ApiResponse.error("Group '" + imported.getName() + "' already exists. Use mode=replace to overwrite."));
                }
            }

            session.getTestSuite().addTestGroup(imported);
            return ResponseEntity.ok(ApiResponse.ok("Group imported: " + imported.getName(), imported));

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to parse XML: " + e.getMessage()));
        }
    }

    // ─── Placeholder endpoints (future: Postman / HAR import) ─────────────────

    @PostMapping("/import/postman")
    public ResponseEntity<ApiResponse<Void>> importPostman(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(ApiResponse.error("Postman import is not yet implemented"));
    }

    @PostMapping("/import/har")
    public ResponseEntity<ApiResponse<Void>> importHar(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(ApiResponse.error("HAR import is not yet implemented"));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private TestGroup findGroup(String name) {
        return session.getTestSuite().getTestGroups().stream()
                .filter(g -> g.getName().equals(name))
                .findFirst().orElse(null);
    }

    private TestGroup requireGroup(String name) {
        TestGroup g = findGroup(name);
        if (g == null) throw new IllegalArgumentException("Group '" + name + "' not found");
        return g;
    }

    private void requireSuite() {
        if (!session.hasSuite()) throw new IllegalStateException("No suite loaded in session");
    }
}