package com.fpt.comparison_tool.controller;

import com.fpt.comparison_tool.dto.ApiResponse;
import com.fpt.comparison_tool.model.*;
import com.fpt.comparison_tool.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/suite")
public class SuiteController {

    private final SessionService session;
    private final ExcelImportService excelImport;
    private final XmlImportService xmlImport;
    private final AuthService authService;

    public SuiteController(SessionService session, ExcelImportService excelImport,
                           XmlImportService xmlImport, AuthService authService) {
        this.session     = session;
        this.excelImport = excelImport;
        this.xmlImport   = xmlImport;
        this.authService = authService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<TestSuite>> getSuite() {
        if (!session.hasSuite()) return ResponseEntity.ok(ApiResponse.error("No suite loaded"));
        return ResponseEntity.ok(ApiResponse.ok(session.getTestSuite()));
    }

    @PostMapping("/import")
    public ResponseEntity<ApiResponse<TestSuite>> importFile(
            @RequestParam("file") MultipartFile file) {
        try {
            String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
            TestSuite suite;
            if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) {
                suite = excelImport.importFrom(file.getInputStream());
            } else if (filename.endsWith(".xml")) {
                suite = xmlImport.importFrom(file.getInputStream());
            } else {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Unsupported file type. Use .xlsx or .xml"));
            }
            session.loadSuite(suite);
            authService.clearCache();
            return ResponseEntity.ok(ApiResponse.ok("Suite imported successfully", suite));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Import failed: " + e.getMessage()));
        }
    }

    @PostMapping("/new")
    public ResponseEntity<ApiResponse<TestSuite>> createNew(@RequestBody TestSuite suite) {
        session.loadSuite(suite);
        authService.clearCache();
        return ResponseEntity.ok(ApiResponse.ok("Suite created", suite));
    }

    @DeleteMapping
    public ResponseEntity<ApiResponse<Void>> clearSuite() {
        session.clearSuite();
        authService.clearCache();
        return ResponseEntity.ok(ApiResponse.ok("Session cleared", null));
    }

    @PutMapping("/settings")
    public ResponseEntity<ApiResponse<SuiteSettings>> updateSettings(
            @RequestBody SuiteSettings settings) {
        requireSuite();
        session.getTestSuite().setSettings(settings);
        return ResponseEntity.ok(ApiResponse.ok(settings));
    }

    /** Update environments list (replaces old single-environment config) */
    @PutMapping("/environment")
    public ResponseEntity<ApiResponse<List<Environment>>> updateEnvironments(
            @RequestBody List<Environment> environments) {
        requireSuite();
        session.getTestSuite().setEnvironments(environments);
        return ResponseEntity.ok(ApiResponse.ok(environments));
    }

    @PutMapping("/auth-profiles")
    public ResponseEntity<ApiResponse<Void>> updateAuthProfiles(
            @RequestBody List<AuthProfile> profiles) {
        requireSuite();
        session.getTestSuite().setAuthProfiles(profiles);
        authService.clearCache();
        return ResponseEntity.ok(ApiResponse.ok("Auth profiles updated", null));
    }

    private void requireSuite() {
        if (!session.hasSuite()) throw new IllegalStateException("No suite loaded in session");
    }
}