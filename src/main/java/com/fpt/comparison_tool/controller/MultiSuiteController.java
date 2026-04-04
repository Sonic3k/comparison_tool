package com.fpt.comparison_tool.controller;

import com.fpt.comparison_tool.dto.ApiResponse;
import com.fpt.comparison_tool.model.TestSuite;
import com.fpt.comparison_tool.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collection;

@RestController
@RequestMapping("/api/suites")
public class MultiSuiteController {

    private final SuiteRegistry      registry;
    private final ExcelImportService excelImport;
    private final XmlImportService   xmlImport;
    private final AuthService        authService;

    public MultiSuiteController(SuiteRegistry registry, ExcelImportService excelImport,
                                 XmlImportService xmlImport, AuthService authService) {
        this.registry    = registry;
        this.excelImport = excelImport;
        this.xmlImport   = xmlImport;
        this.authService = authService;
    }

    @PostMapping("/import")
    public ResponseEntity<ApiResponse<TestSuite>> importSuite(@RequestParam("file") MultipartFile file) {
        try {
            String fn = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
            TestSuite suite;
            if (fn.endsWith(".xlsx") || fn.endsWith(".xls")) {
                suite = excelImport.importFrom(file.getInputStream());
            } else if (fn.endsWith(".xml")) {
                suite = xmlImport.importFrom(file.getInputStream());
            } else {
                return ResponseEntity.badRequest().body(ApiResponse.error("Unsupported file type. Use .xlsx or .xml"));
            }
            registry.register(suite);
            authService.clearCache();
            return ResponseEntity.ok(ApiResponse.ok("Suite imported: " + suite.getSettings().getSuiteName(), suite));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Import failed: " + e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Collection<TestSuite>>> listSuites() {
        return ResponseEntity.ok(ApiResponse.ok(registry.getAll()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TestSuite>> getSuite(@PathVariable String id) {
        return registry.get(id)
                .map(s -> ResponseEntity.ok(ApiResponse.ok(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteSuite(@PathVariable String id) {
        if (!registry.exists(id)) return ResponseEntity.notFound().build();
        registry.remove(id);
        return ResponseEntity.ok(ApiResponse.ok("Suite removed", null));
    }
}
