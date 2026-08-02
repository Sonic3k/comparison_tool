package com.fpt.comparison_tool.service;

import com.fpt.comparison_tool.dto.ExecutionProgress;
import com.fpt.comparison_tool.dto.ExecutionStartRequest;
import com.fpt.comparison_tool.model.Environment;
import com.fpt.comparison_tool.model.ExecutionConfig;
import com.fpt.comparison_tool.model.TestRequest;
import com.fpt.comparison_tool.model.TestSuite;
import com.fpt.comparison_tool.model.VerificationMode;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * CI entrypoint shared by Mode A (REST /api/ci/run) and Mode B (ci-run CLI).
 *
 * A CI run is an assertion-only run against ONE environment:
 *   - the env param picks an environment defined in the suite; it becomes both
 *     source and target, so BOTH-mode TCs stay harmless (they compare the env
 *     against itself) while their assertions run for real
 *   - the suite Verification Mode is forced to AUTOMATION (a filter): TCs whose
 *     own mode is comparison/none are skipped; automation/both TCs run
 *   - setup/teardown phases and Global Setup/Teardown groups run as usual, so
 *     variable extraction/injection behaves exactly like a UI run
 *
 * Stateless: never touches SessionService — safe to run next to UI sessions.
 */
@Service
public class CiRunService {

    private final ExecutionService executionService;
    private final JsonImportService jsonImportService;
    private final XmlImportService xmlImportService;

    public CiRunService(ExecutionService executionService,
                        JsonImportService jsonImportService,
                        XmlImportService xmlImportService) {
        this.executionService  = executionService;
        this.jsonImportService = jsonImportService;
        this.xmlImportService  = xmlImportService;
    }

    public record CiRunResult(TestSuite suite, ExecutionProgress progress) {
        public boolean hasFailures() {
            return progress.getFailed() > 0 || progress.getErrorCount() > 0;
        }
    }

    /** Parse a suite file — format detected by extension, then content sniff. */
    public TestSuite parseSuite(byte[] bytes, String filename) throws Exception {
        String name = filename == null ? "" : filename.toLowerCase();
        String head = new String(bytes, 0, Math.min(bytes.length, 64), StandardCharsets.UTF_8).trim();
        boolean json = name.endsWith(".json") || (!name.endsWith(".xml") && head.startsWith("{"));
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            return json ? jsonImportService.importFrom(in) : xmlImportService.importFrom(in);
        }
    }

    /**
     * Run the suite against one environment. Blocks until done.
     *
     * @param env      name of an environment defined in the suite (required)
     * @param groups   optional group scope; null/empty = all enabled groups
     * @param listener optional per-request completion hook (CLI live log); may be null
     * @throws IllegalArgumentException on config errors (unknown env, missing settings)
     */
    public CiRunResult run(TestSuite suite, String env, List<String> groups,
                           BiConsumer<String, TestRequest> listener) {
        if (env == null || env.isBlank()) {
            throw new IllegalArgumentException(
                    "env is required — the name of an environment defined in the suite");
        }
        if (suite.getSettings() == null || suite.getSettings().getExecutionConfig() == null) {
            throw new IllegalArgumentException(
                    "Suite has no settings.executionConfig — not a valid suite export");
        }
        suite.normalize();
        if (suite.findEnvironment(env) == null) {
            String known = suite.getEnvironments() == null ? "" : suite.getEnvironments().stream()
                    .map(Environment::getName).collect(Collectors.joining(", "));
            throw new IllegalArgumentException(
                    "Environment '" + env + "' not found in suite. Available: [" + known + "]");
        }

        ExecutionConfig ec = suite.getSettings().getExecutionConfig();
        ec.setSourceEnvironment(env);
        ec.setTargetEnvironment(env);
        ec.setVerificationMode(VerificationMode.AUTOMATION);

        ExecutionStartRequest request = new ExecutionStartRequest();
        if (groups != null && !groups.isEmpty()) request.setGroups(groups);

        ExecutionProgress progress = new ExecutionProgress();
        if (listener != null) progress.setListener(listener);
        executionService.runSync(suite, request, progress);
        return new CiRunResult(suite, progress);
    }
}
