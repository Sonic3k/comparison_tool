package com.fpt.comparison_tool.controller;

import com.fpt.comparison_tool.generator.ExcelGenerator;
import com.fpt.comparison_tool.generator.SampleDataBuilder;
import com.fpt.comparison_tool.generator.XmlGenerator;
import com.fpt.comparison_tool.model.ExecutionTask;
import com.fpt.comparison_tool.model.TestSuite;
import com.fpt.comparison_tool.service.SuiteRegistry;
import com.fpt.comparison_tool.service.TaskQueueService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;

/**
 * Export endpoints — suite definitions and task results are separate.
 *
 * Suite (definitions only, no results):
 *   GET /api/suites/{id}/export/excel
 *   GET /api/suites/{id}/export/xml
 *
 * Task results:
 *   GET /api/tasks/{id}/export/excel
 *   GET /api/tasks/{id}/export/xml
 *
 * Templates:
 *   GET /api/export/template/excel
 *   GET /api/export/template/xml
 */
@RestController
public class ExportController {

    private final SuiteRegistry  registry;
    private final TaskQueueService taskQueue;
    private final ExcelGenerator   excelGen = new ExcelGenerator();
    private final XmlGenerator     xmlGen   = new XmlGenerator();

    public ExportController(SuiteRegistry registry, TaskQueueService taskQueue) {
        this.registry  = registry;
        this.taskQueue = taskQueue;
    }

    // ── Suite definition export (no results) ──────────────────────────────────

    @GetMapping("/api/suites/{id}/export/excel")
    public ResponseEntity<byte[]> exportSuiteExcel(@PathVariable String id) throws Exception {
        TestSuite suite = requireSuite(id);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        excelGen.generate(suite, out);
        String name = safeName(suite.getSettings().getSuiteName());
        return file(out.toByteArray(), name + "_Suite.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @GetMapping("/api/suites/{id}/export/xml")
    public ResponseEntity<byte[]> exportSuiteXml(@PathVariable String id) throws Exception {
        TestSuite suite = requireSuite(id);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        xmlGen.generate(suite, out);
        String name = safeName(suite.getSettings().getSuiteName());
        return file(out.toByteArray(), name + "_Suite.xml", "application/xml");
    }

    // ── Task result export ────────────────────────────────────────────────────

    @GetMapping("/api/tasks/{id}/export/excel")
    public ResponseEntity<byte[]> exportTaskExcel(@PathVariable String id) throws Exception {
        ExecutionTask task = requireTask(id);
        TestSuite suite = requireSuite(task.getSuiteId());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        excelGen.generateWithResults(suite, task, out);
        String name = safeName(task.getSuiteName()) + "_Results_" + shortId(id);
        return file(out.toByteArray(), name + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @GetMapping("/api/tasks/{id}/export/xml")
    public ResponseEntity<byte[]> exportTaskXml(@PathVariable String id) throws Exception {
        ExecutionTask task = requireTask(id);
        TestSuite suite = requireSuite(task.getSuiteId());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        xmlGen.generateWithResults(suite, task, out);
        String name = safeName(task.getSuiteName()) + "_Results_" + shortId(id);
        return file(out.toByteArray(), name + ".xml", "application/xml");
    }

    // ── Templates ─────────────────────────────────────────────────────────────

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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private TestSuite requireSuite(String id) {
        return registry.get(id)
                .orElseThrow(() -> new IllegalArgumentException("Suite not found: " + id));
    }

    private ExecutionTask requireTask(String id) {
        return taskQueue.getTask(id)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + id));
    }

    private ResponseEntity<byte[]> file(byte[] data, String filename, String contentType) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(data.length)
                .body(data);
    }

    private String safeName(String name) {
        if (name == null || name.isBlank()) return "Suite";
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }
}
