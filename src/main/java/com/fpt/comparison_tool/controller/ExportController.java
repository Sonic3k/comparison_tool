package com.fpt.comparison_tool.controller;

import com.fpt.comparison_tool.generator.ExcelGenerator;
import com.fpt.comparison_tool.generator.PostmanExporter;
import com.fpt.comparison_tool.generator.PostmanExporter.PostmanExport;
import com.fpt.comparison_tool.generator.SampleDataBuilder;
import com.fpt.comparison_tool.generator.XmlGenerator;
import com.fpt.comparison_tool.model.TestSuite;
import com.fpt.comparison_tool.service.SuiteRegistry;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
public class ExportController {

    private final SuiteRegistry  registry;
    private final ExcelGenerator excelGen    = new ExcelGenerator();
    private final XmlGenerator   xmlGen      = new XmlGenerator();
    private final PostmanExporter postmanExp = new PostmanExporter();

    public ExportController(SuiteRegistry registry) { this.registry = registry; }

    @GetMapping("/api/suites/{id}/export/excel")
    public ResponseEntity<byte[]> exportExcel(@PathVariable String id) throws Exception {
        TestSuite suite = require(id);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        excelGen.generate(suite, out);
        return file(out.toByteArray(), safeName(suite) + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @GetMapping("/api/suites/{id}/export/xml")
    public ResponseEntity<byte[]> exportXml(@PathVariable String id) throws Exception {
        TestSuite suite = require(id);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        xmlGen.generate(suite, out);
        return file(out.toByteArray(), safeName(suite) + ".xml", "application/xml");
    }

    @GetMapping("/api/suites/{id}/export/postman")
    public ResponseEntity<byte[]> exportPostman(@PathVariable String id) throws Exception {
        TestSuite suite = require(id);
        PostmanExport export = postmanExp.export(suite);
        String name = safeName(suite);
        byte[] zip = buildZip(name, export);
        return file(zip, name + "_postman.zip", "application/zip");
    }

    @GetMapping("/api/export/template/excel")
    public ResponseEntity<byte[]> templateExcel() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        excelGen.generate(SampleDataBuilder.build(), out);
        return file(out.toByteArray(), "API_Comparison_Template.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @GetMapping("/api/export/template/xml")
    public ResponseEntity<byte[]> templateXml() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        xmlGen.generate(SampleDataBuilder.build(), out);
        return file(out.toByteArray(), "API_Comparison_Template.xml", "application/xml");
    }

    private TestSuite require(String id) {
        return registry.get(id).orElseThrow(() -> new IllegalArgumentException("Suite not found: " + id));
    }

    private byte[] buildZip(String suiteName, PostmanExport export) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            addZipEntry(zip, suiteName + "_collection.json", export.collectionJson());
            addZipEntry(zip, suiteName + "_source-env.json", export.sourceEnvJson());
            addZipEntry(zip, suiteName + "_target-env.json", export.targetEnvJson());
        }
        return out.toByteArray();
    }

    private void addZipEntry(ZipOutputStream zip, String name, byte[] data) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private ResponseEntity<byte[]> file(byte[] data, String name, String type) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType(type))
                .contentLength(data.length).body(data);
    }

    private String safeName(TestSuite s) {
        String n = s.getSettings() != null ? s.getSettings().getSuiteName() : "Suite";
        return (n == null || n.isBlank() ? "Suite" : n).replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
