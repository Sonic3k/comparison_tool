package com.fpt.comparison_tool.generator;

import com.fpt.comparison_tool.model.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates an Excel workbook from a TestSuite.
 * Sheet order: Settings | Summary | Environments | Auth Profiles | Variables | TC - <GroupName> ...
 */
public class ExcelGenerator {

    public void generate(TestSuite suite, OutputStream out) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Styles s = new Styles(wb);
            writeSettingsSheet(wb, suite.getSettings(), s);
            writeSummarySheet(wb, suite, s);
            writeEnvironmentsSheet(wb, suite.getEnvironments(), s);
            writeAuthProfilesSheet(wb, suite, s);
            writeVariablesSheet(wb, suite, s);
            for (TestGroup group : suite.getTestGroups()) {
                writeTestGroupSheet(wb, group, s);
            }
            wb.write(out);
        }
    }

    // ─── Settings ─────────────────────────────────────────────────────────────

    /** Session-global variables — 3 columns, absent in old workbooks. */
    private void writeVariablesSheet(Workbook wb, TestSuite suite, Styles s) {
        Sheet sheet = wb.createSheet("Variables");
        Row header = sheet.createRow(0);
        setCellStyled(header, 0, "Name",       s.header);
        setCellStyled(header, 1, "Value",      s.header);
        setCellStyled(header, 2, "Updated At", s.header);
        int r = 1;
        for (GlobalVariable v : suite.getGlobalVariables()) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(v.getName()      != null ? v.getName()      : "");
            row.createCell(1).setCellValue(v.getValue()     != null ? v.getValue()     : "");
            row.createCell(2).setCellValue(v.getUpdatedAt() != null ? v.getUpdatedAt() : "");
        }
        sheet.setColumnWidth(0, 24 * 256);
        sheet.setColumnWidth(1, 50 * 256);
        sheet.setColumnWidth(2, 20 * 256);
    }

    private void writeSettingsSheet(Workbook wb, SuiteSettings st, Styles s) {
        Sheet sheet = wb.createSheet("Settings");
        ExecutionConfig ec = st.getExecutionConfig();
        ComparisonConfig cc = st.getComparisonConfig();

        String[][] rows = {
            { "Section", "Field", "Value", "Description" },
            { "", "", "", "" },
            { "Basic Info", "Suite Name",          nvl(st.getSuiteName()),       "Name of this test suite" },
            { "Basic Info", "Description",          nvl(st.getDescription()),     "Description of test suite purpose" },
            { "Basic Info", "Version",              nvl(st.getVersion()),         "Version for tracking changes" },
            { "Basic Info", "Created By",           nvl(st.getCreatedBy()),       "Suite creator" },
            { "Basic Info", "Created Date",         nvl(st.getCreatedDate()),     "Creation date" },
            { "Basic Info", "Last Updated By",      nvl(st.getLastUpdatedBy()),   "Last person to update" },
            { "Basic Info", "Last Updated Date",    nvl(st.getLastUpdatedDate()), "Last update date" },
            { "", "", "", "" },
            { "Execution", "Mode",                  ec.getMode() != null ? ec.getMode().toValue() : "parallel", "parallel or source_first" },
            { "Execution", "Verification Mode",      ec.getVerificationMode() != null ? ec.getVerificationMode().getValue() : "", "Suite-level override: comparison / automation / both. Blank = use per-TC setting" },
            { "Execution", "Timeout",               String.valueOf(ec.getTimeout()),              "Request timeout in seconds" },
            { "Execution", "Parallel Limit",        String.valueOf(ec.getParallelLimit()),        "Max concurrent requests" },
            { "Execution", "Delay Between Requests",String.valueOf(ec.getDelayBetweenRequests()), "Delay in milliseconds" },
            { "Execution", "Retries",               String.valueOf(ec.getRetries()),              "Retry attempts on failure" },
            { "Execution", "Source Environment",    nvl(ec.getSourceEnvironment()),              "Name of source environment" },
            { "Execution", "Target Environment",    nvl(ec.getTargetEnvironment()),              "Name of target environment" },
            { "", "", "", "" },
            { "Comparison", "Ignore Fields",           nvl(cc.getIgnoreFieldsRaw()),                              "Comma-separated fields to skip" },
            { "Comparison", "Case Sensitive",           String.valueOf(cc.isCaseSensitive()).toUpperCase(),         "TRUE or FALSE" },
            { "Comparison", "Ignore Array Order",       String.valueOf(cc.isIgnoreArrayOrder()).toUpperCase(),      "TRUE or FALSE" },
            { "Comparison", "Numeric Tolerance",        String.valueOf(cc.getNumericTolerance()),                   "Tolerance for numeric comparison" },
            { "Comparison", "Compare Error Responses",  String.valueOf(cc.isCompareErrorResponses()).toUpperCase(), "FALSE=treat 5xx as error; TRUE=capture and compare 4xx/5xx responses" },
        };

        for (int r = 0; r < rows.length; r++) {
            Row row = sheet.createRow(r);
            for (int c = 0; c < rows[r].length; c++) {
                Cell cell = row.createCell(c);
                cell.setCellValue(rows[r][c]);
                if (r == 0) cell.setCellStyle(s.header);
                else if (c == 0 && !rows[r][0].isEmpty()) cell.setCellStyle(s.section);
            }
        }
        sheet.setColumnWidth(0, 18 * 256);
        sheet.setColumnWidth(1, 26 * 256);
        sheet.setColumnWidth(2, 44 * 256);
        sheet.setColumnWidth(3, 50 * 256);
    }

    // ─── Summary (sheet #2) ───────────────────────────────────────────────────

    /**
     * Statistics sheet inserted right after Settings. Mirrors the UI overall
     * numbers: disabled groups and fully-disabled test cases are excluded from
     * every figure and reported separately. Ignored on import — config sheets
     * are read by exact name and TC sheets by the "TC - " prefix.
     */
    private void writeSummarySheet(Workbook wb, TestSuite suite, Styles s) {
        Sheet sheet = wb.createSheet("Summary");
        SummaryStyles ss = new SummaryStyles(wb);
        final int cols = 9;

        // ── Compute stats (mirrors frontend tcStats / suiteOverallStats) ──
        List<GroupStat> gstats = new ArrayList<>();
        List<FailedTc> failures = new ArrayList<>();
        int total = 0, passed = 0, failed = 0, error = 0, reqs = 0;
        int disabledTcs = 0, disabledGroups = 0;

        for (TestGroup g : suite.getTestGroups()) {
            GroupStat gs = new GroupStat();
            gs.name = g.getName();
            gs.enabled = g.isEnabled();

            Map<String, TestCaseDef> defs = new HashMap<>();
            if (g.getTestCaseDefs() != null) {
                for (TestCaseDef d : g.getTestCaseDefs()) defs.put(d.getId(), d);
            }

            for (Map.Entry<String, List<TestRequest>> chunk : tcChunks(g).entrySet()) {
                List<TestRequest> en = new ArrayList<>();
                for (TestRequest req : chunk.getValue()) if (req.isEnabled()) en.add(req);
                if (en.isEmpty()) { if (g.isEnabled()) disabledTcs++; continue; }

                gs.total++;
                gs.reqs += en.size();
                String roll = rollup(en);
                switch (roll) {
                    case "passed" -> gs.passed++;
                    case "failed" -> gs.failed++;
                    case "error"  -> gs.error++;
                }
                if (g.isEnabled() && (roll.equals("failed") || roll.equals("error"))) {
                    failures.add(buildFailedTc(g.getName(), chunk.getKey(), en, roll, defs));
                }
            }

            if (g.isEnabled()) {
                total += gs.total; passed += gs.passed; failed += gs.failed;
                error += gs.error; reqs += gs.reqs;
            } else {
                disabledGroups++;
                disabledTcs += tcChunks(g).size();
            }
            gstats.add(gs);
        }

        int pending = total - passed - failed - error;
        int passRate = total > 0 ? Math.round(passed * 100f / total) : 0;

        // ── Title ──
        int r = 0;
        String suiteName = suite.getSettings() != null ? nvl(suite.getSettings().getSuiteName()) : "";
        Row title = sheet.createRow(r);
        title.setHeightInPoints(26);
        setCellStyled(title, 0, "EXECUTION SUMMARY" + (suiteName.isEmpty() ? "" : " — " + suiteName), ss.title);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(r, r, 0, cols - 1));
        r++;

        Row gen = sheet.createRow(r);
        setCellStyled(gen, 0, "Generated at " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), ss.muted);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(r, r, 0, cols - 1));
        r += 2;

        // ── OVERALL ──
        r = writeBanner(sheet, r, "OVERALL  (enabled test cases only)", ss.banner, cols);
        Row lh = sheet.createRow(r++);
        String[] labels = {"Pass Rate", "Total TCs", "Passed", "Failed", "Error", "Pending", "Requests"};
        for (int i = 0; i < labels.length; i++) setCellStyled(lh, i, labels[i], ss.label);

        Row lv = sheet.createRow(r++);
        lv.setHeightInPoints(24);
        CellStyle rateStyle = failed + error > 0 ? ss.rateRed : (passRate == 100 ? ss.rateGreen : ss.rateAmber);
        setCellStyled(lv, 0, passRate + "%", rateStyle);
        setNumCell(lv, 1, total,   ss.valPlain);
        setNumCell(lv, 2, passed,  passed  > 0 ? ss.valGreen  : ss.valGray);
        setNumCell(lv, 3, failed,  failed  > 0 ? ss.valRed    : ss.valGray);
        setNumCell(lv, 4, error,   error   > 0 ? ss.valOrange : ss.valGray);
        setNumCell(lv, 5, pending, pending > 0 ? ss.valAmber  : ss.valGray);
        setNumCell(lv, 6, reqs,    ss.valPlain);

        if (disabledTcs > 0 || disabledGroups > 0) {
            Row dn = sheet.createRow(r++);
            String note = "Disabled — excluded from every number above: " + disabledTcs + " test case(s)"
                    + (disabledGroups > 0 ? " · " + disabledGroups + " group(s)" : "");
            setCellStyled(dn, 0, note, ss.muted);
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(dn.getRowNum(), dn.getRowNum(), 0, cols - 1));
        }
        r++; // blank

        // ── GROUP BREAKDOWN ──
        r = writeBanner(sheet, r, "GROUP BREAKDOWN", ss.banner, cols);
        Row gh = sheet.createRow(r++);
        String[] gHeaders = {"Group", "State", "Test Cases", "Passed", "Failed", "Error", "Pending", "Pass %", "Requests"};
        for (int i = 0; i < gHeaders.length; i++) setCellStyled(gh, i, gHeaders[i], ss.label);

        for (GroupStat gs : gstats) {
            Row row = sheet.createRow(r++);
            if (gs.enabled) {
                setCellStyled(row, 0, gs.name, ss.cellLeft);
                setCellStyled(row, 1, "enabled", ss.stateOn);
                setNumCell(row, 2, gs.total,     ss.valPlain);
                setNumCell(row, 3, gs.passed,    gs.passed    > 0 ? ss.valGreen  : ss.valGray);
                setNumCell(row, 4, gs.failed,    gs.failed    > 0 ? ss.valRed    : ss.valGray);
                setNumCell(row, 5, gs.error,     gs.error     > 0 ? ss.valOrange : ss.valGray);
                setNumCell(row, 6, gs.pending(), gs.pending() > 0 ? ss.valAmber  : ss.valGray);
                CellStyle grStyle = gs.failed + gs.error > 0 ? ss.valRed
                        : (gs.passRate() == 100 ? ss.valGreen : ss.valAmber);
                setCellStyled(row, 7, gs.passRate() + "%", grStyle);
                setNumCell(row, 8, gs.reqs, ss.valPlain);
            } else {
                setCellStyled(row, 0, gs.name, ss.disabledLeft);
                setCellStyled(row, 1, "disabled", ss.disabledCenter);
                setNumCell(row, 2, gs.total,     ss.disabledCenter);
                setNumCell(row, 3, gs.passed,    ss.disabledCenter);
                setNumCell(row, 4, gs.failed,    ss.disabledCenter);
                setNumCell(row, 5, gs.error,     ss.disabledCenter);
                setNumCell(row, 6, gs.pending(), ss.disabledCenter);
                setCellStyled(row, 7, "—", ss.disabledCenter);
                setNumCell(row, 8, gs.reqs, ss.disabledCenter);
            }
        }

        Row tot = sheet.createRow(r++);
        setCellStyled(tot, 0, "TOTAL (enabled)", ss.totalLabel);
        setCellStyled(tot, 1, "", ss.totalVal);
        setNumCell(tot, 2, total,   ss.totalVal);
        setNumCell(tot, 3, passed,  ss.totalVal);
        setNumCell(tot, 4, failed,  ss.totalVal);
        setNumCell(tot, 5, error,   ss.totalVal);
        setNumCell(tot, 6, pending, ss.totalVal);
        setCellStyled(tot, 7, passRate + "%", ss.totalVal);
        setNumCell(tot, 8, reqs,    ss.totalVal);
        r++; // blank

        // ── FAILED / ERROR TEST CASES ──
        r = writeBanner(sheet, r, "FAILED / ERROR TEST CASES", ss.bannerRed, cols);
        if (failures.isEmpty()) {
            Row ok = sheet.createRow(r++);
            boolean executed = passed + failed + error > 0;
            setCellStyled(ok, 0, executed ? "✓ No failed or error test cases" : "No executions yet",
                    executed ? ss.okLine : ss.muted);
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(ok.getRowNum(), ok.getRowNum(), 0, cols - 1));
        } else {
            Row fh = sheet.createRow(r++);
            String[] fHeaders = {"Group", "Test Case", "Name", "Status", "Detail"};
            for (int i = 0; i < fHeaders.length; i++) setCellStyled(fh, i, fHeaders[i], ss.label);
            for (int i = 5; i < cols; i++) setCellStyled(fh, i, "", ss.label);
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(fh.getRowNum(), fh.getRowNum(), 4, cols - 1));

            for (FailedTc f : failures) {
                Row row = sheet.createRow(r++);
                setCellStyled(row, 0, f.group,  ss.cellLeft);
                setCellStyled(row, 1, f.tcId,   ss.cellLeft);
                setCellStyled(row, 2, f.name,   ss.cellLeft);
                setCellStyled(row, 3, f.status, "error".equals(f.status) ? ss.valOrange : ss.valRed);
                setCellStyled(row, 4, f.detail, ss.detail);
                for (int i = 5; i < cols; i++) setCellStyled(row, i, "", ss.detail);
                sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(row.getRowNum(), row.getRowNum(), 4, cols - 1));
            }
        }

        int[] widths = {30, 11, 11, 9, 10, 10, 10, 9, 14};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
    }

    private int writeBanner(Sheet sheet, int rowIdx, String text, CellStyle style, int cols) {
        Row row = sheet.createRow(rowIdx);
        row.setHeightInPoints(20);
        setCellStyled(row, 0, text, style);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(rowIdx, rowIdx, 0, cols - 1));
        return rowIdx + 1;
    }

    private void setNumCell(Row row, int col, int value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    /** Chunk a group's requests by testCaseId (first-appearance order) — mirrors frontend tcChunks(). */
    private Map<String, List<TestRequest>> tcChunks(TestGroup group) {
        Map<String, List<TestRequest>> chunks = new LinkedHashMap<>();
        if (group.getTestRequests() == null) return chunks;
        for (TestRequest req : group.getTestRequests()) {
            String key = req.getTestCaseId() != null && !req.getTestCaseId().isBlank() ? req.getTestCaseId() : req.getId();
            chunks.computeIfAbsent(key, k -> new ArrayList<>()).add(req);
        }
        return chunks;
    }

    /** Rolled-up status of a test case from its enabled requests — mirrors frontend tcRollupStatus(). */
    private String rollup(List<TestRequest> enabledReqs) {
        boolean anyFailed = false, anyError = false, allPassed = !enabledReqs.isEmpty();
        for (TestRequest req : enabledReqs) {
            ExecutionStatus st = req.getResult() != null && req.getResult().getStatus() != null
                    ? req.getResult().getStatus() : ExecutionStatus.PENDING;
            if (st == ExecutionStatus.ERROR)  anyError  = true;
            if (st == ExecutionStatus.FAILED) anyFailed = true;
            if (st != ExecutionStatus.PASSED) allPassed = false;
        }
        if (anyError)  return "error";
        if (anyFailed) return "failed";
        if (allPassed) return "passed";
        return "pending";
    }

    private FailedTc buildFailedTc(String group, String tcId, List<TestRequest> en, String roll,
                                   Map<String, TestCaseDef> defs) {
        FailedTc f = new FailedTc();
        f.group = group;
        f.tcId = tcId;
        f.status = roll;

        TestRequest firstBad = en.get(0);
        for (TestRequest req : en) {
            TestResult res = req.getResult();
            if (res != null && (res.getStatus() == ExecutionStatus.FAILED || res.getStatus() == ExecutionStatus.ERROR)) {
                firstBad = req;
                break;
            }
        }

        TestCaseDef def = defs.get(tcId);
        if (def != null && def.getName() != null && !def.getName().isBlank() && !def.getName().equals(tcId)) {
            f.name = def.getName();
        } else if (en.size() == 1) {
            f.name = nvl(firstBad.getName());
        } else {
            f.name = "";
        }

        TestResult res = firstBad.getResult();
        String raw = "";
        if (res != null) {
            if (res.getErrorMessage() != null && !res.getErrorMessage().isBlank()) {
                raw = res.getErrorMessage();
            } else if (res.getComparisonResult() != null && !res.getComparisonResult().isBlank()) {
                raw = res.getComparisonResult();
            } else if (res.getAssertionResult() != null && !res.getAssertionResult().isBlank()) {
                for (String line : res.getAssertionResult().split("\n")) {
                    if (!line.trim().startsWith("✓")) { raw = line; break; }
                }
                if (raw.isBlank()) raw = res.getAssertionResult();
            }
        }
        String first = raw.split("\n")[0].trim();
        if (first.length() > 220) first = first.substring(0, 217) + "...";
        f.detail = first;
        return f;
    }

    /** Per-group TC-level stats for the Summary sheet. */
    private static class GroupStat {
        String name;
        boolean enabled;
        int total, passed, failed, error, reqs;
        int pending()  { return total - passed - failed - error; }
        int passRate() { return total > 0 ? Math.round(passed * 100f / total) : 0; }
    }

    private static class FailedTc {
        String group, tcId, name, status, detail;
    }

    /** Styles used only by the Summary sheet. */
    private static class SummaryStyles {
        final CellStyle title, muted, banner, bannerRed, label,
                valPlain, valGreen, valRed, valOrange, valAmber, valGray,
                rateGreen, rateAmber, rateRed,
                cellLeft, stateOn, disabledLeft, disabledCenter,
                totalLabel, totalVal, detail, okLine;

        SummaryStyles(Workbook wb) {
            title          = mk(wb, IndexedColors.DARK_BLUE, IndexedColors.WHITE, true, 14, HorizontalAlignment.LEFT, true);
            muted          = mk(wb, null, IndexedColors.GREY_50_PERCENT, false, 10, HorizontalAlignment.LEFT, false);
            banner         = mk(wb, IndexedColors.DARK_BLUE, IndexedColors.WHITE, true, 12, HorizontalAlignment.LEFT, true);
            bannerRed      = mk(wb, IndexedColors.DARK_RED, IndexedColors.WHITE, true, 12, HorizontalAlignment.LEFT, true);
            label          = mk(wb, IndexedColors.GREY_25_PERCENT, IndexedColors.BLACK, true, 11, HorizontalAlignment.CENTER, true);
            valPlain       = mk(wb, null, IndexedColors.BLACK, true, 11, HorizontalAlignment.CENTER, true);
            valGreen       = mk(wb, null, IndexedColors.GREEN, true, 11, HorizontalAlignment.CENTER, true);
            valRed         = mk(wb, null, IndexedColors.RED, true, 11, HorizontalAlignment.CENTER, true);
            valOrange      = mk(wb, null, IndexedColors.ORANGE, true, 11, HorizontalAlignment.CENTER, true);
            valAmber       = mk(wb, null, IndexedColors.DARK_YELLOW, true, 11, HorizontalAlignment.CENTER, true);
            valGray        = mk(wb, null, IndexedColors.GREY_40_PERCENT, false, 11, HorizontalAlignment.CENTER, true);
            rateGreen      = mk(wb, IndexedColors.LIGHT_GREEN, IndexedColors.DARK_GREEN, true, 14, HorizontalAlignment.CENTER, true);
            rateAmber      = mk(wb, IndexedColors.LEMON_CHIFFON, IndexedColors.DARK_YELLOW, true, 14, HorizontalAlignment.CENTER, true);
            rateRed        = mk(wb, IndexedColors.ROSE, IndexedColors.DARK_RED, true, 14, HorizontalAlignment.CENTER, true);
            cellLeft       = mk(wb, null, IndexedColors.BLACK, false, 11, HorizontalAlignment.LEFT, true);
            stateOn        = mk(wb, null, IndexedColors.GREEN, false, 10, HorizontalAlignment.CENTER, true);
            disabledLeft   = mk(wb, null, IndexedColors.GREY_40_PERCENT, false, 11, HorizontalAlignment.LEFT, true);
            disabledCenter = mk(wb, null, IndexedColors.GREY_40_PERCENT, false, 11, HorizontalAlignment.CENTER, true);
            totalLabel     = mk(wb, IndexedColors.GREY_25_PERCENT, IndexedColors.BLACK, true, 11, HorizontalAlignment.LEFT, true);
            totalVal       = mk(wb, IndexedColors.GREY_25_PERCENT, IndexedColors.BLACK, true, 11, HorizontalAlignment.CENTER, true);
            detail         = mk(wb, null, IndexedColors.BLACK, false, 10, HorizontalAlignment.LEFT, true);
            okLine         = mk(wb, null, IndexedColors.GREEN, true, 11, HorizontalAlignment.LEFT, false);
        }

        private static CellStyle mk(Workbook wb, IndexedColors bg, IndexedColors fg,
                                    boolean bold, int size, HorizontalAlignment align, boolean border) {
            CellStyle st = wb.createCellStyle();
            Font f = wb.createFont();
            f.setBold(bold);
            f.setColor(fg.getIndex());
            f.setFontHeightInPoints((short) size);
            st.setFont(f);
            if (bg != null) {
                st.setFillForegroundColor(bg.getIndex());
                st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            if (border) {
                st.setBorderBottom(BorderStyle.THIN);
                st.setBorderTop(BorderStyle.THIN);
                st.setBorderLeft(BorderStyle.THIN);
                st.setBorderRight(BorderStyle.THIN);
            }
            st.setAlignment(align);
            st.setVerticalAlignment(VerticalAlignment.CENTER);
            return st;
        }
    }

    // ─── Environments ──────────────────────────────────────────────────────────
    // Columns: Name | URL | Auth Profile | Headers (encoded "Key:Value, Key2:Value2")

    private void writeEnvironmentsSheet(Workbook wb, List<Environment> envs, Styles s) {
        Sheet sheet = wb.createSheet("Environments");

        Row header = sheet.createRow(0);
        setCellStyled(header, 0, "Name",         s.header);
        setCellStyled(header, 1, "URL",           s.header);
        setCellStyled(header, 2, "Auth Profile",  s.header);
        setCellStyled(header, 3, "Headers",       s.header);
        setCellStyled(header, 4, "Variables",     s.header);

        int rowIdx = 1;
        for (Environment env : envs) {
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(nvl(env.getName()));
            row.createCell(1).setCellValue(nvl(env.getUrl()));
            row.createCell(2).setCellValue(nvl(env.getAuthProfile()));
            // Encode List<Param> → "Key:Value, Key2:Value2"
            String encoded = env.getHeaders() == null ? "" :
                    env.getHeaders().stream()
                       .map(p -> p.getKey() + ":" + p.getValue())
                       .collect(Collectors.joining(", "));
            row.createCell(3).setCellValue(encoded);
            // Encode List<Param> → one "key=value" per line
            String vars = env.getVariables() == null ? "" :
                    env.getVariables().stream()
                       .map(p -> p.getKey() + "=" + p.getValue())
                       .collect(Collectors.joining("\n"));
            row.createCell(4).setCellValue(vars);
        }

        sheet.setColumnWidth(0, 22 * 256);
        sheet.setColumnWidth(1, 44 * 256);
        sheet.setColumnWidth(2, 22 * 256);
        sheet.setColumnWidth(3, 70 * 256);
        sheet.setColumnWidth(4, 50 * 256);
    }

    // ─── Auth Profiles ────────────────────────────────────────────────────────

    private void writeAuthProfilesSheet(Workbook wb, TestSuite suite, Styles s) {
        Sheet sheet = wb.createSheet("Auth Profiles");

        String[] cols = { "Profile Name", "Auth Type", "Description", "Token URL",
                "Username", "Password", "Client ID", "Client Secret",
                "Scope", "Entity ID", "Token", "Additional Config" };
        Row hdr = sheet.createRow(0);
        for (int i = 0; i < cols.length; i++) setCellStyled(hdr, i, cols[i], s.header);

        int r = 1;
        for (AuthProfile p : suite.getAuthProfiles()) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(nvl(p.getName()));
            row.createCell(1).setCellValue(p.getType() != null ? p.getType().name().toLowerCase() : "none");
            row.createCell(2).setCellValue(nvl(p.getDescription()));
            row.createCell(3).setCellValue(nvl(p.getTokenUrl()));
            row.createCell(4).setCellValue(nvl(p.getUsername()));
            row.createCell(5).setCellValue(nvl(p.getPassword()));
            row.createCell(6).setCellValue(nvl(p.getClientId()));
            row.createCell(7).setCellValue(nvl(p.getClientSecret()));
            row.createCell(8).setCellValue(nvl(p.getScope()));
            row.createCell(9).setCellValue(nvl(p.getEntityId()));
            row.createCell(10).setCellValue(nvl(p.getToken()));
            row.createCell(11).setCellValue(nvl(p.getAdditionalConfig()));
        }

        int[] widths = {15, 18, 30, 35, 20, 15, 15, 15, 20, 20, 25, 20};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
    }

    // ─── TC Sheet ─────────────────────────────────────────────────────────────

    private void writeTestGroupSheet(Workbook wb, TestGroup group, Styles s) {
        Sheet sheet = wb.createSheet("TC - " + group.getName());
        int totalCols = 32;

        // Row 0: group info header
        Row r0 = sheet.createRow(0);
        setCellStyled(r0, 0, "GROUP INFO — TC - " + group.getName(), s.groupHeader);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, totalCols - 1));

        // Rows 1-3: metadata
        writeGroupInfoRow(sheet, 1, "Group Name",  group.getName(),        totalCols, s);
        writeGroupInfoRow(sheet, 2, "Description", group.getDescription(), totalCols, s);
        writeGroupInfoRow(sheet, 3, "Owner",        group.getOwner(),       totalCols, s);

        // Row 4: blank
        sheet.createRow(4);

        // Row 5: section banners
        // GREEN   0-16: REQUEST DEFINITION (incl. Test Case ID, Auth Profile, Delay (ms))
        // TEAL   17-21: COMPARISON OVERRIDES
        // PURPLE 22-25: AUTOMATION ASSERTIONS
        // RED    26-31: EXECUTION RESULTS
        Row r5 = sheet.createRow(5);
        setCellStyled(r5, 0,  "TEST CASE DEFINITION",   s.tcHeader);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(5, 5, 0, 16));
        setCellStyled(r5, 17, "COMPARISON OVERRIDES",   s.cmpHeader);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(5, 5, 17, 21));
        setCellStyled(r5, 22, "AUTOMATION ASSERTIONS",  s.autoHeader);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(5, 5, 22, 25));
        setCellStyled(r5, 26, "EXECUTION RESULTS",      s.resultHeader);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(5, 5, 26, totalCols - 1));

        // Row 6: column headers (32 cols)
        String[] headers = {
            // GREEN 0-16
            "ID", "Test Case ID", "Name", "Description", "Enabled", "Verification Mode", "Phase",
            "Method", "Endpoint", "Query Params", "Form Params", "JSON Body",
            "Headers", "Author", "Extract Variables", "Auth Profile", "Delay (ms)",
            // TEAL 17-21
            "Ignore Fields", "Ignore Array Order", "Compare Error Responses",
            "Numeric Tolerance", "Case Sensitive",
            // PURPLE 22-25
            "Expected Status", "Expected Body (Assertions)", "Expected Headers", "Max Response Time (ms)",
            // RED 26-31
            "Overall Status", "Mode Run", "Comparison Result", "Assertion Result", "Executed At",
            "Response Time (ms)"
        };
        Row r6 = sheet.createRow(6);
        for (int i = 0; i < headers.length; i++) {
            CellStyle cs = i <= 16 ? s.tcHeader : (i <= 21 ? s.cmpHeader : (i <= 25 ? s.autoHeader : s.resultHeader));
            setCellStyled(r6, i, headers[i], cs);
        }

        // Rows 7+: test requests
        int rowIdx = 7;
        for (TestRequest tc : group.getTestRequests()) {
            Row row = sheet.createRow(rowIdx++);

            // GREEN 0-15
            row.createCell(0).setCellValue(nvl(tc.getId()));
            row.createCell(1).setCellValue(nvl(tc.getTestCaseId()));
            row.createCell(2).setCellValue(nvl(tc.getName()));
            row.createCell(3).setCellValue(nvl(tc.getDescription()));
            row.createCell(4).setCellValue(String.valueOf(tc.isEnabled()).toUpperCase());
            row.createCell(5).setCellValue(tc.getVerificationMode() != null ? tc.getVerificationMode().getValue() : "comparison");
            row.createCell(6).setCellValue(tc.getPhase() != null ? tc.getPhase().getValue() : "test");
            row.createCell(7).setCellValue(tc.getMethod() != null ? tc.getMethod().name() : "GET");
            row.createCell(8).setCellValue(nvl(tc.getEndpoint()));
            row.createCell(9).setCellValue(tc.getQueryParamsAsString());
            row.createCell(10).setCellValue(tc.getFormParamsAsString());
            row.createCell(11).setCellValue(nvl(tc.getJsonBody()));
            row.createCell(12).setCellValue(nvl(tc.getHeaders()));
            row.createCell(13).setCellValue(nvl(tc.getAuthor()));
            row.createCell(14).setCellValue(nvl(tc.getExtractVariables()));
            row.createCell(15).setCellValue(nvl(tc.getAuthProfile()));
            row.createCell(16).setCellValue(tc.getDelayMs() > 0 ? String.valueOf(tc.getDelayMs()) : "");

            // TEAL 17-21
            ComparisonConfig cmp = tc.getComparisonConfig();
            row.createCell(17).setCellValue(cmp != null ? nvl(cmp.getIgnoreFieldsRaw()) : "");
            row.createCell(18).setCellValue(cmp != null ? String.valueOf(cmp.isIgnoreArrayOrder()) : "");
            row.createCell(19).setCellValue(cmp != null ? String.valueOf(cmp.isCompareErrorResponses()).toUpperCase() : "");
            row.createCell(20).setCellValue(cmp != null ? String.valueOf(cmp.getNumericTolerance()) : "");
            row.createCell(21).setCellValue(cmp != null ? String.valueOf(cmp.isCaseSensitive()) : "");

            // PURPLE 22-25
            AutomationConfig auto = tc.getAutomationConfig();
            row.createCell(22).setCellValue(auto != null ? nvl(auto.getExpectedStatus()) : "");
            row.createCell(23).setCellValue(auto != null ? nvl(auto.getExpectedBody()) : "");
            row.createCell(24).setCellValue(auto != null ? nvl(auto.getExpectedHeaders()) : "");
            row.createCell(25).setCellValue(auto != null && auto.getMaxResponseTime() > 0 ? String.valueOf(auto.getMaxResponseTime()) : "");

            // RED 26-31
            TestResult res = tc.getResult();
            row.createCell(26).setCellValue(res != null && res.getStatus() != null ? res.getStatus().name().toLowerCase() : "");
            row.createCell(27).setCellValue(res != null ? nvl(res.getModeRun()) : "");
            row.createCell(28).setCellValue(res != null ? nvl(res.getComparisonResult()) : "");
            row.createCell(29).setCellValue(res != null ? nvl(res.getAssertionResult()) : "");
            row.createCell(30).setCellValue(res != null ? nvl(res.getExecutedAt()) : "");
            row.createCell(31).setCellValue(res != null ? formatResponseTimes(res) : "");
        }

        // Column widths (32 cols)
        int[] widths = {
            9, 14, 22, 36, 8, 13, 9, 8, 28, 22, 18, 30, 18, 20, 28, 18, 11,   // GREEN 0-16
            16, 15, 16, 13, 12,                                                 // TEAL 17-21
            13, 36, 20, 14,                                                      // PURPLE 22-25
            12, 13, 36, 36, 18, 16                                               // RED 26-31
        };
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
    }

    private void writeGroupInfoRow(Sheet sheet, int rowIdx, String label, String value, int totalCols, Styles s) {
        Row row = sheet.createRow(rowIdx);
        setCellStyled(row, 0, label, s.groupLabel);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(rowIdx, rowIdx, 0, 1));
        setCellStyled(row, 2, nvl(value), s.groupValue);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(rowIdx, rowIdx, 2, totalCols - 1));
    }

    // ─── Cell helpers ─────────────────────────────────────────────────────────

    private void setCellStyled(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    /** "src 120 · tgt 95" (comparison/both) or "95" (automation/none). */
    private static String formatResponseTimes(TestResult res) {
        Long src = res.getSourceTimeMs(), tgt = res.getTargetTimeMs();
        if (src != null && tgt != null) return "src " + src + " \u00b7 tgt " + tgt;
        if (tgt != null) return String.valueOf(tgt);
        if (src != null) return "src " + src;
        return "";
    }

    private String nvl(String s) { return s != null ? s : ""; }

    // ─── Styles ───────────────────────────────────────────────────────────────

    static class Styles {
        final CellStyle header, section, groupHeader, groupLabel, groupValue, tcHeader, cmpHeader, autoHeader, resultHeader;

        Styles(Workbook wb) {
            header      = build(wb, IndexedColors.DARK_BLUE,  IndexedColors.WHITE, true,  12, false);
            section     = build(wb, IndexedColors.LIGHT_BLUE, IndexedColors.DARK_BLUE, true, 11, false);
            groupHeader = build(wb, IndexedColors.DARK_BLUE,  IndexedColors.WHITE, true,  13, true);
            groupLabel  = build(wb, IndexedColors.LIGHT_CORNFLOWER_BLUE, IndexedColors.DARK_BLUE, true, 11, false);
            groupValue  = build(wb, IndexedColors.LEMON_CHIFFON, IndexedColors.BLACK, false, 11, false);
            tcHeader    = build(wb, IndexedColors.DARK_GREEN, IndexedColors.WHITE, true, 12, true);
            cmpHeader   = build(wb, IndexedColors.TEAL,       IndexedColors.WHITE, true, 12, true);
            autoHeader  = build(wb, IndexedColors.VIOLET,     IndexedColors.WHITE, true, 12, true);
            resultHeader= build(wb, IndexedColors.DARK_RED,   IndexedColors.WHITE, true, 12, true);
        }

        private CellStyle build(Workbook wb, IndexedColors bg, IndexedColors fg,
                                boolean bold, int fontSize, boolean center) {
            CellStyle style = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(bold);
            font.setColor(fg.getIndex());
            font.setFontHeightInPoints((short) fontSize);
            style.setFont(font);
            style.setFillForegroundColor(bg.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            if (center) style.setAlignment(HorizontalAlignment.CENTER);
            return style;
        }
    }
}