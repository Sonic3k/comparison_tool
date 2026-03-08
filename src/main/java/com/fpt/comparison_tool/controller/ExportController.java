package com.fpt.comparison_tool.controller;

import com.fpt.comparison_tool.generator.ExcelGenerator;
import com.fpt.comparison_tool.generator.SampleDataBuilder;
import com.fpt.comparison_tool.generator.XmlGenerator;
import com.fpt.comparison_tool.service.SessionService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;

@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final SessionService session;
    private final ExcelGenerator excelGenerator = new ExcelGenerator();
    private final XmlGenerator   xmlGenerator   = new XmlGenerator();

    public ExportController(SessionService session) {
        this.session = session;
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

    // ─── Helper ───────────────────────────────────────────────────────────────

    private ResponseEntity<byte[]> file(byte[] data, String filename, String contentType) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(data.length)
                .body(data);
    }

    private void requireSuite() {
        if (!session.hasSuite()) throw new IllegalStateException("No suite loaded in session");
    }
}