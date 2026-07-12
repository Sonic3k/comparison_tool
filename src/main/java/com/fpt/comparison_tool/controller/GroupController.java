package com.fpt.comparison_tool.controller;

import com.fpt.comparison_tool.dto.ApiResponse;
import com.fpt.comparison_tool.generator.BrunoExporter;
import com.fpt.comparison_tool.generator.BrunoExporter.BrunoExport;
import com.fpt.comparison_tool.generator.PostmanExporter;
import com.fpt.comparison_tool.generator.PostmanExporter.PostmanExport;
import com.fpt.comparison_tool.generator.XmlGenerator;
import com.fpt.comparison_tool.model.TestGroup;
import com.fpt.comparison_tool.model.TestSuite;
import com.fpt.comparison_tool.service.JsonImportService;
import com.fpt.comparison_tool.service.SessionService;
import com.fpt.comparison_tool.service.XmlImportService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private final SessionService    session;
    private final XmlImportService  xmlImport       = new XmlImportService();
    private final JsonImportService jsonImport      = new JsonImportService();
    private final XmlGenerator      xmlGenerator    = new XmlGenerator();
    private final PostmanExporter   postmanExporter = new PostmanExporter();
    private final BrunoExporter     brunoExporter   = new BrunoExporter();

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

    // ─── Export single group as Postman Collection ────────────────────────────

    /**
     * GET /api/groups/{name}/export/postman?mode=source|target|both
     *
     * Re-uses PostmanExporter by building a synthetic TestSuite containing only
     * this group. Settings, environments and auth profiles are reused from the
     * current session so generated auth + base URL are identical to a full
     * suite export.
     */
    @GetMapping("/{name}/export/postman")
    public ResponseEntity<byte[]> exportGroupPostman(
            @PathVariable("name") String name,
            @RequestParam(defaultValue = "target") String mode) throws Exception {
        requireSuite();
        TestGroup group = requireGroup(name);
        TestSuite synthetic = buildSyntheticSuite(group);
        String safeName = name.replaceAll("[^a-zA-Z0-9_-]", "_");

        return switch (mode.toLowerCase()) {
            case "source" -> file(postmanExporter.exportSingle(synthetic, true),
                    safeName + "_source_collection.json", "application/json");
            case "target" -> file(postmanExporter.exportSingle(synthetic, false),
                    safeName + "_target_collection.json", "application/json");
            case "both"   -> {
                PostmanExport e = postmanExporter.exportBoth(synthetic);
                yield file(zip(
                        zipEntry(safeName + "_collection.json", e.collectionJson()),
                        zipEntry(safeName + "_source-env.json", e.sourceEnvJson()),
                        zipEntry(safeName + "_target-env.json", e.targetEnvJson())),
                        safeName + "_postman.zip", "application/zip");
            }
            default -> throw new IllegalArgumentException("Invalid mode: " + mode);
        };
    }

    // ─── Export single group as Bruno (OpenAPI YAML + env JSON) ───────────────

    @GetMapping("/{name}/export/bruno")
    public ResponseEntity<byte[]> exportGroupBruno(
            @PathVariable("name") String name,
            @RequestParam(defaultValue = "target") String mode) throws Exception {
        requireSuite();
        TestGroup group = requireGroup(name);
        TestSuite synthetic = buildSyntheticSuite(group);
        String safeName = name.replaceAll("[^a-zA-Z0-9_-]", "_");

        return switch (mode.toLowerCase()) {
            case "source" -> file(brunoExporter.exportSingle(synthetic, true),
                    safeName + "_source_bruno.yaml", "application/yaml");
            case "target" -> file(brunoExporter.exportSingle(synthetic, false),
                    safeName + "_target_bruno.yaml", "application/yaml");
            case "both"   -> {
                BrunoExport e = brunoExporter.exportBoth(synthetic);
                yield file(zip(
                        zipEntry(safeName + "_collection.yaml", e.collectionYaml()),
                        zipEntry(safeName + "_source-env.json", e.sourceEnvJson()),
                        zipEntry(safeName + "_target-env.json", e.targetEnvJson())),
                        safeName + "_bruno.zip", "application/zip");
            }
            default -> throw new IllegalArgumentException("Invalid mode: " + mode);
        };
    }

    private TestSuite buildSyntheticSuite(TestGroup group) {
        TestSuite full = session.getTestSuite();
        TestSuite copy = new TestSuite();
        copy.setSettings(full.getSettings());
        copy.setEnvironments(full.getEnvironments());
        copy.setAuthProfiles(full.getAuthProfiles());
        List<TestGroup> oneGroup = new ArrayList<>();
        oneGroup.add(group);
        copy.setTestGroups(oneGroup);
        return copy;
    }

    private ResponseEntity<byte[]> file(byte[] data, String filename, String contentType) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(data.length)
                .body(data);
    }

    private record ZipEntry2(String name, byte[] data) {}
    private ZipEntry2 zipEntry(String name, byte[] data) { return new ZipEntry2(name, data); }
    private byte[] zip(ZipEntry2... entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (ZipEntry2 e : entries) {
                zip.putNextEntry(new ZipEntry(e.name()));
                zip.write(e.data());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
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
            return applyImport(imported, mode);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to parse XML: " + e.getMessage()));
        }
    }

    // ─── Import single group from JSON ────────────────────────────────────────

    @PostMapping("/import/json")
    public ResponseEntity<ApiResponse<TestGroup>> importGroupJson(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mode", defaultValue = "new") String mode) {
        requireSuite();
        try {
            TestGroup imported = jsonImport.importGroup(file.getInputStream());
            if (imported.getName() == null || imported.getName().isBlank()) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Group name is missing in JSON"));
            }
            return applyImport(imported, mode);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to parse JSON: " + e.getMessage()));
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

    private ResponseEntity<ApiResponse<TestGroup>> applyImport(TestGroup imported, String mode) {
        imported.normalize();
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
    }

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