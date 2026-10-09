package com.fpt.comparison_tool.report;

import com.fpt.comparison_tool.result.AssertionResult;
import com.fpt.comparison_tool.result.CaseResult;
import com.fpt.comparison_tool.result.RunData;
import com.fpt.comparison_tool.result.Status;
import com.fpt.comparison_tool.result.StepResult;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JUnit XML for CI: one testsuite per folder, one testcase per test case (fail = failure, error / blocked = error). */
public final class JUnitReport {

    private JUnitReport() {
    }

    public static void write(RunData data, OutputStream os) throws IOException {
        Map<String, List<CaseResult>> byFolder = new LinkedHashMap<>();
        for (CaseResult c : data.cases()) {
            String folder = c.path() == null || c.path().isEmpty() ? data.header().suite() : String.join("/", c.path());
            byFolder.computeIfAbsent(folder, k -> new ArrayList<>()).add(c);
        }
        Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8);
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        w.write("<testsuites name=\"" + esc(data.header().suite()) + "\" tests=\"" + data.cases().size() + "\">\n");
        for (Map.Entry<String, List<CaseResult>> e : byFolder.entrySet()) {
            List<CaseResult> cases = e.getValue();
            long failures = cases.stream().filter(c -> c.statusEnum() == Status.FAIL).count();
            long errors = cases.stream().filter(c -> c.statusEnum() == Status.ERROR || c.statusEnum() == Status.BLOCKED).count();
            long skipped = cases.stream().filter(c -> c.statusEnum() == Status.SKIPPED).count();
            double time = cases.stream().mapToLong(c -> c.durationMs() == null ? 0 : c.durationMs()).sum() / 1000.0;
            w.write(String.format("  <testsuite name=\"%s\" tests=\"%d\" failures=\"%d\" errors=\"%d\" skipped=\"%d\" time=\"%.3f\">%n",
                    esc(e.getKey()), cases.size(), failures, errors, skipped, time));
            for (CaseResult c : cases) {
                String name = c.key() == null || c.key().isBlank() ? c.name() : c.key() + (c.name() == null || c.name().equals(c.key()) ? "" : " " + c.name());
                w.write(String.format("    <testcase classname=\"%s\" name=\"%s\" time=\"%.3f\"", esc(e.getKey()), esc(name),
                        (c.durationMs() == null ? 0 : c.durationMs()) / 1000.0));
                Status s = c.statusEnum();
                if (s == Status.PASS) {
                    w.write("/>\n");
                    continue;
                }
                w.write(">\n");
                String tag = switch (s) {
                    case FAIL -> "failure";
                    case SKIPPED -> "skipped";
                    default -> "error";
                };
                w.write("      <" + tag + " message=\"" + esc(c.reason()) + "\" type=\"" + s.value() + "\">");
                w.write(esc(stepLog(c)));
                w.write("</" + tag + ">\n");
                w.write("    </testcase>\n");
            }
            w.write("  </testsuite>\n");
        }
        w.write("</testsuites>\n");
        w.flush();
    }

    private static String stepLog(CaseResult c) {
        StringBuilder sb = new StringBuilder();
        if (c.steps() == null) return sb.toString();
        for (StepResult st : c.steps()) {
            sb.append(st.phase()).append(' ').append(st.index() + 1).append(' ').append(st.status().toUpperCase())
                    .append(' ').append(st.name());
            if (st.target() != null && st.target().status() != null) sb.append(" [HTTP ").append(st.target().status()).append(']');
            sb.append('\n');
            if (st.reason() != null && st.statusEnum() != Status.PASS) sb.append("    ").append(st.reason()).append('\n');
            if (st.assertions() != null) {
                for (AssertionResult a : st.assertions()) {
                    if (!a.pass()) sb.append("    x ").append(a.expr()).append(" -> ").append(a.message()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> {
                    if (ch < 0x20 && ch != '\n' && ch != '\t' && ch != '\r') sb.append(' ');
                    else sb.append(ch);
                }
            }
        }
        return sb.toString();
    }
}
