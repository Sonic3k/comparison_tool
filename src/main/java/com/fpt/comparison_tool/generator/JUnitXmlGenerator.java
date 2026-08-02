package com.fpt.comparison_tool.generator;

import com.fpt.comparison_tool.model.ExecutionStatus;
import com.fpt.comparison_tool.model.Phase;
import com.fpt.comparison_tool.model.TestGroup;
import com.fpt.comparison_tool.model.TestRequest;
import com.fpt.comparison_tool.model.TestResult;
import com.fpt.comparison_tool.model.TestSuite;
import com.fpt.comparison_tool.model.VerificationMode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Turns an executed suite into a JUnit XML report — the format GitLab
 * (artifacts:reports:junit) and Jenkins (junit step) parse natively.
 *
 * Mapping:
 *   group                    → <testsuite>
 *   request                  → <testcase classname="group" name="id — name">
 *   FAILED                   → <failure> with the assertion lines
 *   ERROR                    → <error> with the transport/config reason
 *   disabled / filtered /
 *   never executed (PENDING) → <skipped> with the reason
 *
 * groupScope mirrors the CI run's group filter; disabled groups still appear
 * (fully skipped) so the report tells the whole story.
 */
@Component
public class JUnitXmlGenerator {

    public String generate(TestSuite suite, List<String> groupScope) {
        StringBuilder suites = new StringBuilder();
        int tTests = 0, tFail = 0, tErr = 0, tSkip = 0;
        double tTime = 0;

        for (TestGroup g : suite.getTestGroups()) {
            if (groupScope != null && !groupScope.isEmpty() && !groupScope.contains(g.getName())) continue;

            StringBuilder cases = new StringBuilder();
            int tests = 0, fail = 0, err = 0, skip = 0;
            double time = 0;

            for (TestRequest r : g.getTestRequests()) {
                tests++;
                TestResult res = r.getResult();
                ExecutionStatus st = !g.isEnabled() || !r.isEnabled() || res == null || res.getStatus() == null
                        ? ExecutionStatus.PENDING : res.getStatus();
                double t = res != null && res.getTargetTimeMs() != null ? res.getTargetTimeMs() / 1000.0 : 0;
                time += t;

                cases.append("    <testcase classname=\"").append(esc(g.getName()))
                     .append("\" name=\"").append(esc(caseName(r)))
                     .append("\" time=\"").append(fmt(t)).append('"');

                switch (st) {
                    case PASSED -> cases.append("/>\n");
                    case FAILED -> {
                        fail++;
                        String detail = res.getAssertionResult() != null ? res.getAssertionResult() : "";
                        if ("both".equals(res.getModeRun()) && notBlank(res.getComparisonResult())) {
                            detail = detail + "\n--- comparison ---\n" + res.getComparisonResult();
                        }
                        cases.append(">\n      <failure message=\"").append(esc(firstLine(detail)))
                             .append("\">").append(esc(detail)).append("</failure>\n    </testcase>\n");
                    }
                    case ERROR -> {
                        err++;
                        String reason = notBlank(res.getErrorMessage())     ? res.getErrorMessage()
                                      : notBlank(res.getComparisonResult()) ? res.getComparisonResult()
                                      : notBlank(res.getAssertionResult())  ? res.getAssertionResult()
                                      : "error";
                        cases.append(">\n      <error message=\"").append(esc(firstLine(reason)))
                             .append("\">").append(esc(reason)).append("</error>\n    </testcase>\n");
                    }
                    default -> {
                        skip++;
                        cases.append(">\n      <skipped message=\"").append(esc(skipReason(g, r)))
                             .append("\"/>\n    </testcase>\n");
                    }
                }
            }

            suites.append("  <testsuite name=\"").append(esc(g.getName()))
                  .append("\" tests=\"").append(tests)
                  .append("\" failures=\"").append(fail)
                  .append("\" errors=\"").append(err)
                  .append("\" skipped=\"").append(skip)
                  .append("\" time=\"").append(fmt(time))
                  .append("\">\n").append(cases).append("  </testsuite>\n");

            tTests += tests; tFail += fail; tErr += err; tSkip += skip; tTime += time;
        }

        String suiteName = suite.getSettings() != null && suite.getSettings().getSuiteName() != null
                ? suite.getSettings().getSuiteName() : "API Test Suite";

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<testsuites name=\"" + esc(suiteName)
                + "\" tests=\"" + tTests + "\" failures=\"" + tFail + "\" errors=\"" + tErr
                + "\" skipped=\"" + tSkip + "\" time=\"" + fmt(tTime) + "\">\n"
                + suites + "</testsuites>\n";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String caseName(TestRequest r) {
        String id = r.getId() != null ? r.getId() : "?";
        return notBlank(r.getName()) ? id + " — " + r.getName() : id;
    }

    private String skipReason(TestGroup g, TestRequest r) {
        if (!g.isEnabled()) return "group disabled";
        if (!r.isEnabled()) return "disabled";
        VerificationMode m = r.getVerificationMode() != null ? r.getVerificationMode() : VerificationMode.COMPARISON;
        Phase p = r.getPhase() != null ? r.getPhase() : Phase.TEST;
        if (p == Phase.TEST && (m == VerificationMode.COMPARISON || m == VerificationMode.NONE)) {
            return "verificationMode=" + m.getValue() + " — CI runs automation assertions only";
        }
        return "not executed";
    }

    private String fmt(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }

    private String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        String line = nl >= 0 ? s.substring(0, nl) : s;
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** XML-escape; control chars (common in raw response bodies) become spaces. */
    private String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&'  -> b.append("&amp;");
                case '<'  -> b.append("&lt;");
                case '>'  -> b.append("&gt;");
                case '"'  -> b.append("&quot;");
                default -> {
                    if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') b.append(' ');
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }
}
