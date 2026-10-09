package com.fpt.comparison_tool.report;

import com.fpt.comparison_tool.result.AssertionResult;
import com.fpt.comparison_tool.result.CaseResult;
import com.fpt.comparison_tool.result.HookResult;
import com.fpt.comparison_tool.result.RunData;
import com.fpt.comparison_tool.result.RunHeader;
import com.fpt.comparison_tool.result.Status;
import com.fpt.comparison_tool.result.StepResult;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Run report workbook: Summary, Not Passed (with triage labels), Test Cases, Steps and Setup-Teardown sheets. */
public final class ExcelReport {

    private static final int CELL_LIMIT = 32_000;

    private ExcelReport() {
    }

    public static void write(RunData data, OutputStream os) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            summary(wb, st, data);
            notPassed(wb, st, data);
            cases(wb, st, data);
            steps(wb, st, data);
            hooks(wb, st, data);
            wb.write(os);
        }
    }

    private static final class Styles {
        final CellStyle header;
        final CellStyle wrap;
        final Map<Status, CellStyle> status = new EnumMap<>(Status.class);

        Styles(XSSFWorkbook wb) {
            Font bold = wb.createFont();
            bold.setBold(true);
            bold.setColor(IndexedColors.WHITE.getIndex());
            header = wb.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.SEA_GREEN.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            wrap = wb.createCellStyle();
            wrap.setWrapText(true);
            wrap.setVerticalAlignment(VerticalAlignment.TOP);
            colour(wb, Status.PASS, IndexedColors.LIGHT_GREEN);
            colour(wb, Status.FAIL, IndexedColors.ROSE);
            colour(wb, Status.ERROR, IndexedColors.LIGHT_ORANGE);
            colour(wb, Status.BLOCKED, IndexedColors.GREY_25_PERCENT);
            colour(wb, Status.SKIPPED, IndexedColors.GREY_25_PERCENT);
        }

        private void colour(XSSFWorkbook wb, Status s, IndexedColors c) {
            CellStyle cs = wb.createCellStyle();
            cs.setFillForegroundColor(c.getIndex());
            cs.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            cs.setVerticalAlignment(VerticalAlignment.TOP);
            status.put(s, cs);
        }
    }

    private static Sheet table(XSSFWorkbook wb, Styles st, String name, String[] cols, int[] widths) {
        Sheet s = wb.createSheet(name);
        Row r = s.createRow(0);
        for (int i = 0; i < cols.length; i++) {
            Cell c = r.createCell(i);
            c.setCellValue(cols[i]);
            c.setCellStyle(st.header);
            s.setColumnWidth(i, Math.min(255, widths[i]) * 256);
        }
        s.createFreezePane(0, 1);
        return s;
    }

    private static void put(Row r, int col, Object v, CellStyle style) {
        if (v == null) return;
        Cell c = r.createCell(col);
        if (v instanceof Number n) c.setCellValue(n.doubleValue());
        else {
            String t = v.toString();
            c.setCellValue(t.length() > CELL_LIMIT ? t.substring(0, CELL_LIMIT) + "..." : t);
        }
        if (style != null) c.setCellStyle(style);
    }

    private static void summary(XSSFWorkbook wb, Styles st, RunData data) {
        Sheet s = table(wb, st, "Summary", new String[]{"Item", "Value"}, new int[]{28, 70});
        RunHeader h = data.header();
        Map<String, Integer> counts = data.counts();
        Object[][] rows = {
                {"Suite", h.suite()}, {"Environment", h.env()}, {"Baseline", h.baselineEnv()}, {"Run", h.runId()},
                {"Started", h.startedAt()}, {"Finished", data.summary() == null ? null : data.summary().finishedAt()},
                {"Duration s", data.summary() == null || data.summary().durationMs() == null ? null : data.summary().durationMs() / 1000.0},
                {"Selection", h.selection()}, {"Test cases", data.cases().size()},
                {"Pass", counts.get("pass")}, {"Fail", counts.get("fail")}, {"Error", counts.get("error")},
                {"Blocked", counts.get("blocked")}, {"Skipped", counts.get("skipped")},
        };
        int i = 1;
        for (Object[] row : rows) {
            Row r = s.createRow(i++);
            put(r, 0, row[0], null);
            put(r, 1, row[1], null);
        }
        i++;
        Row head = s.createRow(i++);
        String[] cols = {"Folder", "Total", "Pass", "Fail", "Error", "Blocked", "Skipped"};
        for (int c = 0; c < cols.length; c++) put(head, c, cols[c], st.header);
        Map<String, int[]> byFolder = new LinkedHashMap<>();
        for (CaseResult c : data.cases()) {
            String top = c.path() == null || c.path().isEmpty() ? "(suite root)" : c.path().get(0);
            int[] n = byFolder.computeIfAbsent(top, k -> new int[6]);
            n[0]++;
            n[1 + c.statusEnum().ordinal()]++;
        }
        for (Map.Entry<String, int[]> e : byFolder.entrySet()) {
            Row r = s.createRow(i++);
            put(r, 0, e.getKey(), null);
            for (int c = 0; c < 6; c++) put(r, c + 1, e.getValue()[c], null);
        }
        s.setColumnWidth(0, 60 * 256);
    }

    private static void notPassed(XSSFWorkbook wb, Styles st, RunData data) {
        Sheet s = table(wb, st, "Not Passed", new String[]{"Folder", "Key", "Test Case", "Status", "Failed Step", "Reason",
                "Failed Assertions", "Label", "Note"}, new int[]{40, 18, 50, 10, 40, 80, 80, 14, 40});
        int i = 1;
        for (CaseResult c : data.notPassed()) {
            Row r = s.createRow(i++);
            StepResult failed = c.steps() == null ? null : c.steps().stream()
                    .filter(x -> x.statusEnum() != Status.PASS && x.statusEnum() != Status.SKIPPED).findFirst().orElse(null);
            put(r, 0, folder(c), st.wrap);
            put(r, 1, c.key(), st.wrap);
            put(r, 2, c.name(), st.wrap);
            put(r, 3, c.status(), st.status.get(c.statusEnum()));
            put(r, 4, failed == null ? null : failed.phase() + " " + (failed.index() + 1) + " " + failed.name(), st.wrap);
            put(r, 5, c.reason(), st.wrap);
            put(r, 6, failed == null ? null : failedAssertions(failed), st.wrap);
            put(r, 7, c.triage() == null ? null : c.triage().label(), st.wrap);
            put(r, 8, c.triage() == null ? null : c.triage().note(), st.wrap);
        }
        s.setAutoFilter(new CellRangeAddress(0, Math.max(0, i - 1), 0, 8));
    }

    private static void cases(XSSFWorkbook wb, Styles st, RunData data) {
        Sheet s = table(wb, st, "Test Cases", new String[]{"Folder", "Key", "Test Case", "Status", "Duration ms", "Reason", "Tags"},
                new int[]{40, 18, 60, 10, 12, 80, 20});
        int i = 1;
        for (CaseResult c : data.cases()) {
            Row r = s.createRow(i++);
            put(r, 0, folder(c), null);
            put(r, 1, c.key(), null);
            put(r, 2, c.name(), null);
            put(r, 3, c.status(), st.status.get(c.statusEnum()));
            put(r, 4, c.durationMs(), null);
            put(r, 5, c.reason(), null);
            put(r, 6, c.tags() == null ? null : String.join(", ", c.tags()), null);
        }
        s.setAutoFilter(new CellRangeAddress(0, Math.max(0, i - 1), 0, 6));
    }

    private static void steps(XSSFWorkbook wb, Styles st, RunData data) {
        Sheet s = table(wb, st, "Steps", new String[]{"Folder", "Key", "Phase", "#", "Step", "Status", "Method", "URL", "HTTP",
                "Time ms", "Reason", "Failed Assertions"}, new int[]{36, 16, 9, 5, 40, 9, 8, 60, 7, 9, 60, 60});
        int i = 1;
        for (CaseResult c : data.cases()) {
            if (c.steps() == null) continue;
            for (StepResult x : c.steps()) {
                Row r = s.createRow(i++);
                put(r, 0, folder(c), null);
                put(r, 1, c.key(), null);
                put(r, 2, x.phase(), null);
                put(r, 3, x.index() + 1, null);
                put(r, 4, x.name(), null);
                put(r, 5, x.status(), st.status.get(x.statusEnum()));
                if (x.target() != null) {
                    put(r, 6, x.target().method(), null);
                    put(r, 7, x.target().url(), null);
                    put(r, 8, x.target().status(), null);
                    put(r, 9, x.target().timeMs(), null);
                }
                put(r, 10, x.statusEnum() == Status.PASS ? null : x.reason(), null);
                put(r, 11, failedAssertions(x), null);
            }
        }
        s.setAutoFilter(new CellRangeAddress(0, Math.max(0, i - 1), 0, 11));
    }

    private static void hooks(XSSFWorkbook wb, Styles st, RunData data) {
        if (data.hooks().isEmpty()) return;
        Sheet s = table(wb, st, "Setup-Teardown", new String[]{"Where", "Hook", "Status", "Steps", "Reason"},
                new int[]{50, 10, 10, 8, 90});
        int i = 1;
        for (HookResult h : data.hooks()) {
            Row r = s.createRow(i++);
            put(r, 0, h.path() == null || h.path().isEmpty() ? "(suite)" : String.join(" / ", h.path()), null);
            put(r, 1, h.hook(), null);
            put(r, 2, h.status(), st.status.get(h.statusEnum()));
            put(r, 3, h.steps() == null ? 0 : h.steps().size(), null);
            put(r, 4, h.reason(), null);
        }
    }

    private static String folder(CaseResult c) {
        return c.path() == null ? "" : String.join(" / ", c.path());
    }

    private static String failedAssertions(StepResult x) {
        if (x.assertions() == null) return null;
        List<String> lines = new ArrayList<>();
        for (AssertionResult a : x.assertions()) if (!a.pass()) lines.add(a.expr() + " -> " + a.message());
        if (x.diffs() != null) lines.addAll(x.diffs().stream().limit(20).toList());
        return lines.isEmpty() ? null : String.join("\n", lines);
    }
}
