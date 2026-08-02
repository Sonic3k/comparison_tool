package com.fpt.comparison_tool.cli;

import com.fpt.comparison_tool.dto.ExecutionProgress;
import com.fpt.comparison_tool.generator.JUnitXmlGenerator;
import com.fpt.comparison_tool.model.ExecutionStatus;
import com.fpt.comparison_tool.model.TestRequest;
import com.fpt.comparison_tool.model.TestResult;
import com.fpt.comparison_tool.model.TestSuite;
import com.fpt.comparison_tool.service.CiRunService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Mode B — CI as a CLI. Same jar as the web app:
 *
 *   java -jar comparison-tool.jar ci-run --suite=test-suites/regression.json \
 *        --env=staging [--groups="User APIs,Order APIs"] [--report=junit-report.xml]
 *
 * Prints one live line per request (like mvn test), writes a JUnit XML report,
 * and exits with:
 *   0 — all executed tests passed
 *   1 — at least one failed/error test
 *   2 — config/usage error (bad args, unparseable suite, unknown env, aborted run)
 *
 * Without the ci-run argument the app boots as the normal web server and this
 * runner does nothing.
 */
@Component
public class CiCliRunner implements ApplicationRunner, ExitCodeGenerator {

    private final CiRunService ciRunService;
    private final JUnitXmlGenerator junitGenerator;
    private int exitCode = 0;

    public CiCliRunner(CiRunService ciRunService, JUnitXmlGenerator junitGenerator) {
        this.ciRunService   = ciRunService;
        this.junitGenerator = junitGenerator;
    }

    @Override
    public int getExitCode() { return exitCode; }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.getNonOptionArgs().contains("ci-run")) return;   // web mode — nothing to do
        try {
            exitCode = ciRun(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Config error: " + e.getMessage());
            exitCode = 2;
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e);
            exitCode = 2;
        }
    }

    private int ciRun(ApplicationArguments args) throws Exception {
        String suitePath = option(args, "suite");
        String env       = option(args, "env");
        if (suitePath == null || env == null) {
            System.err.println("Usage: java -jar comparison-tool.jar ci-run"
                    + " --suite=<file.json|.xml> --env=<name>"
                    + " [--groups=\"A,B\"] [--report=junit-report.xml]");
            return 2;
        }
        String groupsCsv  = option(args, "groups");
        String reportPath = option(args, "report");
        if (reportPath == null) reportPath = "junit-report.xml";

        Path file = Path.of(suitePath);
        if (!Files.isRegularFile(file)) {
            System.err.println("Suite file not found: " + suitePath);
            return 2;
        }
        TestSuite suite = ciRunService.parseSuite(Files.readAllBytes(file), file.getFileName().toString());

        List<String> groups = groupsCsv == null || groupsCsv.isBlank() ? null
                : Arrays.stream(groupsCsv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();

        System.out.println("Running suite '" + suitePath + "' against env '" + env + "'"
                + (groups != null ? " groups " + groups : "") + " …");

        CiRunService.CiRunResult result = ciRunService.run(suite, env, groups, this::logLine);
        ExecutionProgress p = result.progress();

        if (p.getError() != null) {
            System.err.println("Run aborted: " + p.getError());
            return 2;
        }

        Path report = Path.of(reportPath);
        if (report.getParent() != null) Files.createDirectories(report.getParent());
        Files.writeString(report, junitGenerator.generate(result.suite(), groups));

        System.out.println("──────────────────────────────────────────────");
        System.out.printf("Total %d · Passed %d · Failed %d · Errors %d · %.1fs%n",
                p.getTotal(), p.getPassed(), p.getFailed(), p.getErrorCount(), p.getElapsedMs() / 1000.0);
        System.out.println("JUnit report: " + report.toAbsolutePath());

        return result.hasFailures() ? 1 : 0;
    }

    /** One live line per finished request, plus indented failure detail. */
    private void logLine(String group, TestRequest r) {
        TestResult res = r.getResult();
        ExecutionStatus st = res != null && res.getStatus() != null ? res.getStatus() : ExecutionStatus.PENDING;
        String tag = switch (st) {
            case PASSED -> "[PASS]";
            case FAILED -> "[FAIL]";
            case ERROR  -> "[ERR ]";
            default     -> "[SKIP]";
        };
        long   ms   = res != null && res.getTargetTimeMs() != null ? res.getTargetTimeMs() : 0;
        String http = res != null && res.getTargetStatus() != null ? res.getTargetStatus() : "-";
        String name = r.getName() != null && !r.getName().isBlank() ? " — " + r.getName() : "";
        System.out.printf("%s %s / %s%s (%s, %dms)%n", tag, group, r.getId(), name, http, ms);

        if (st == ExecutionStatus.FAILED && res.getAssertionResult() != null) {
            for (String line : res.getAssertionResult().split("\n")) {
                System.out.println("        " + line);
            }
        } else if (st == ExecutionStatus.ERROR) {
            String reason = res != null && res.getErrorMessage() != null ? res.getErrorMessage()
                    : res != null && res.getComparisonResult() != null ? res.getComparisonResult() : "";
            if (!reason.isBlank()) System.out.println("        " + reason);
        }
    }

    private String option(ApplicationArguments args, String name) {
        List<String> v = args.getOptionValues(name);
        return v == null || v.isEmpty() ? null : String.join(",", v);
    }
}
