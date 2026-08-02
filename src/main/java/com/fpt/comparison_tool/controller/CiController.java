package com.fpt.comparison_tool.controller;

import com.fpt.comparison_tool.dto.ExecutionProgress;
import com.fpt.comparison_tool.generator.JUnitXmlGenerator;
import com.fpt.comparison_tool.model.TestSuite;
import com.fpt.comparison_tool.service.CiRunService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.List;

/**
 * Mode A — CI over REST. One synchronous call:
 *
 *   POST /api/ci/run
 *     header     X-API-Key = value of the CI_API_KEY env var on the server
 *     multipart  suite     = suite file (.json or .xml)
 *     param      env       = environment name defined in the suite
 *     param      groups    = optional comma-separated group names
 *
 * Response body is always plain text on errors, otherwise the JUnit XML
 * report (save it and feed it to GitLab artifacts:reports:junit or the
 * Jenkins junit step):
 *   200 — all executed tests passed
 *   422 — at least one failed/error test (report still in body)
 *   400 — config error: unparseable suite, unknown env, nothing to run
 *   401 — wrong/missing X-API-Key
 *   503 — CI_API_KEY not configured on the server (endpoint disabled)
 *
 * Summary counters also come back as response headers:
 *   X-CI-Total / X-CI-Passed / X-CI-Failed / X-CI-Errors
 *
 * Stateless — independent of any UI session; the suite travels with the
 * request and nothing is stored server-side.
 *
 * GitLab example:
 *   curl --fail-with-body -o junit.xml -H "X-API-Key: $CI_TOOL_KEY" \
 *        -F "suite=@test-suites/regression.json" \
 *        "$TOOL_URL/api/ci/run?env=staging"
 */
@RestController
@RequestMapping("/api/ci")
public class CiController {

    /** Explicit UTF-8 — StringHttpMessageConverter would otherwise write
     *  ISO-8859-1 while the report declares encoding="UTF-8". */
    private static final MediaType XML_UTF8  = new MediaType("application", "xml",  java.nio.charset.StandardCharsets.UTF_8);
    private static final MediaType TEXT_UTF8 = new MediaType("text",        "plain", java.nio.charset.StandardCharsets.UTF_8);

    private final CiRunService ciRunService;
    private final JUnitXmlGenerator junitGenerator;
    private final String apiKey;

    public CiController(CiRunService ciRunService,
                        JUnitXmlGenerator junitGenerator,
                        @Value("${CI_API_KEY:}") String apiKey) {
        this.ciRunService   = ciRunService;
        this.junitGenerator = junitGenerator;
        this.apiKey         = apiKey;
    }

    @PostMapping("/run")
    public ResponseEntity<String> run(@RequestParam("suite") MultipartFile suiteFile,
                                      @RequestParam("env") String env,
                                      @RequestParam(value = "groups", required = false) String groupsCsv,
                                      @RequestHeader(value = "X-API-Key", required = false) String key) {
        if (apiKey == null || apiKey.isBlank()) {
            return text(HttpStatus.SERVICE_UNAVAILABLE,
                    "CI endpoint disabled — set the CI_API_KEY environment variable on the server");
        }
        if (!apiKey.equals(key)) {
            return text(HttpStatus.UNAUTHORIZED, "Invalid or missing X-API-Key header");
        }

        TestSuite suite;
        try {
            suite = ciRunService.parseSuite(suiteFile.getBytes(), suiteFile.getOriginalFilename());
        } catch (Exception e) {
            return text(HttpStatus.BAD_REQUEST, "Cannot parse suite file: " + e.getMessage());
        }

        List<String> groups = groupsCsv == null || groupsCsv.isBlank() ? null
                : Arrays.stream(groupsCsv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();

        CiRunService.CiRunResult result;
        try {
            result = ciRunService.run(suite, env, groups, null);
        } catch (IllegalArgumentException e) {
            return text(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        ExecutionProgress p = result.progress();
        if (p.getError() != null) {
            return text(HttpStatus.BAD_REQUEST, "Run aborted: " + p.getError());
        }

        String junit = junitGenerator.generate(result.suite(), groups);
        return ResponseEntity.status(result.hasFailures() ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.OK)
                .header("X-CI-Total",  String.valueOf(p.getTotal()))
                .header("X-CI-Passed", String.valueOf(p.getPassed()))
                .header("X-CI-Failed", String.valueOf(p.getFailed()))
                .header("X-CI-Errors", String.valueOf(p.getErrorCount()))
                .contentType(XML_UTF8)
                .body(junit);
    }

    private ResponseEntity<String> text(HttpStatus status, String msg) {
        return ResponseEntity.status(status).contentType(TEXT_UTF8).body(msg);
    }
}
