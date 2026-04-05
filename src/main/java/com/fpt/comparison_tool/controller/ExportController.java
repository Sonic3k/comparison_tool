package com.fpt.comparison_tool.controller;

import com.fpt.comparison_tool.generator.ExcelGenerator;
import com.fpt.comparison_tool.generator.JmeterExporter;
import com.fpt.comparison_tool.generator.JmeterExporter.JmxExport;
import com.fpt.comparison_tool.generator.PostmanExporter;
import com.fpt.comparison_tool.generator.PostmanExporter.PostmanExport;
import com.fpt.comparison_tool.generator.SampleDataBuilder;
import com.fpt.comparison_tool.generator.XmlGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.comparison_tool.model.TestSuite;
import com.fpt.comparison_tool.service.SessionService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final SessionService  session;
    private final ExcelGenerator  excelGenerator  = new ExcelGenerator();
    private final XmlGenerator    xmlGenerator    = new XmlGenerator();
    private final PostmanExporter postmanExporter = new PostmanExporter();
    private final JmeterExporter  jmeterExporter  = new JmeterExporter();
    private final ObjectMapper    objectMapper    = new ObjectMapper();

    public ExportController(SessionService session) { this.session = session; }

    @GetMapping("/json")
    public ResponseEntity<byte[]> exportJson() throws Exception {
        requireSuite();
        byte[] data = objectMapper.writerWithDefaultPrettyPrinter()
                                  .writeValueAsBytes(session.getTestSuite());
        return file(data, safeName(session.getTestSuite()) + ".json", "application/json");
    }

    @GetMapping("/excel")
    public ResponseEntity<byte[]> exportExcel() throws Exception {
        requireSuite();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        excelGenerator.generate(session.getTestSuite(), out);
        return file(out.toByteArray(), "API_Comparison_Results.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @GetMapping("/xml")
    public ResponseEntity<byte[]> exportXml() throws Exception {
        requireSuite();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        xmlGenerator.generate(session.getTestSuite(), out);
        return file(out.toByteArray(), "API_Comparison_Results.xml", "application/xml");
    }

    @GetMapping("/postman")
    public ResponseEntity<byte[]> exportPostman(
            @RequestParam(defaultValue = "target") String mode) throws Exception {
        requireSuite();
        TestSuite suite = session.getTestSuite();
        String name = safeName(suite);
        return switch (mode.toLowerCase()) {
            case "source" -> file(postmanExporter.exportSingle(suite, true),
                    name + "_source_collection.json", "application/json");
            case "target" -> file(postmanExporter.exportSingle(suite, false),
                    name + "_target_collection.json", "application/json");
            case "both"   -> {
                PostmanExport e = postmanExporter.exportBoth(suite);
                yield file(zip(name,
                        entry(name + "_collection.json", e.collectionJson()),
                        entry(name + "_source-env.json", e.sourceEnvJson()),
                        entry(name + "_target-env.json", e.targetEnvJson())),
                        name + "_postman.zip", "application/zip");
            }
            default -> throw new IllegalArgumentException("Invalid mode: " + mode);
        };
    }

    @GetMapping("/jmeter")
    public ResponseEntity<byte[]> exportJmeter(
            @RequestParam(defaultValue = "target") String mode) throws Exception {
        requireSuite();
        TestSuite suite = session.getTestSuite();
        String name = safeName(suite);
        return switch (mode.toLowerCase()) {
            case "source" -> file(jmeterExporter.exportSingle(suite, true),
                    name + "_source.jmx", "application/xml");
            case "target" -> file(jmeterExporter.exportSingle(suite, false),
                    name + "_target.jmx", "application/xml");
            case "both"   -> {
                JmxExport e = jmeterExporter.exportBoth(suite);
                yield file(zip(name,
                        entry(name + "_source.jmx", e.sourceJmx()),
                        entry(name + "_target.jmx", e.targetJmx())),
                        name + "_jmeter.zip", "application/zip");
            }
            default -> throw new IllegalArgumentException("Invalid mode: " + mode);
        };
    }

    @GetMapping("/template/excel")
    public ResponseEntity<byte[]> templateExcel() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        excelGenerator.generate(SampleDataBuilder.build(), out);
        return file(out.toByteArray(), "API_Comparison_Template.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @GetMapping("/template/xml")
    public ResponseEntity<byte[]> templateXml() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        xmlGenerator.generate(SampleDataBuilder.build(), out);
        return file(out.toByteArray(), "API_Comparison_Template.xml", "application/xml");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<byte[]> file(byte[] data, String filename, String contentType) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(data.length)
                .body(data);
    }

    private record ZipEntry2(String name, byte[] data) {}

    private ZipEntry2 entry(String name, byte[] data) { return new ZipEntry2(name, data); }

    private byte[] zip(String ignored, ZipEntry2... entries) throws Exception {
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

    private String safeName(TestSuite suite) {
        String n = suite.getSettings() != null ? suite.getSettings().getSuiteName() : null;
        return (n != null && !n.isBlank() ? n : "Suite").replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private void requireSuite() {
        if (!session.hasSuite()) throw new IllegalStateException("No suite loaded in session");
    }
}