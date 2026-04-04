package com.fpt.comparison_tool.generator;

import com.fpt.comparison_tool.model.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates an Excel workbook from a TestSuite.
 * Sheet order: Settings | Environments | Auth Profiles | TC - <GroupName> ...
 */
public class ExcelGenerator {

    public void generate(TestSuite suite, OutputStream out) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Styles s = new Styles(wb);
            writeSettingsSheet(wb, suite.getSettings(), s);
            writeEnvironmentsSheet(wb, suite.getEnvironments(), s);
            writeAuthProfilesSheet(wb, suite, s);
            for (TestGroup group : suite.getTestGroups()) {
                writeTestGroupSheet(wb, group, s);
            }
            wb.write(out);
        }
    }

    // ─── Settings ─────────────────────────────────────────────────────────────

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

    // ─── Environments ──────────────────────────────────────────────────────────
    // Columns: Name | URL | Auth Profile | Headers (encoded "Key:Value, Key2:Value2")

    private void writeEnvironmentsSheet(Workbook wb, List<Environment> envs, Styles s) {
        Sheet sheet = wb.createSheet("Environments");

        Row header = sheet.createRow(0);
        setCellStyled(header, 0, "Name",         s.header);
        setCellStyled(header, 1, "URL",           s.header);
        setCellStyled(header, 2, "Auth Profile",  s.header);
        setCellStyled(header, 3, "Headers",       s.header);

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
        }

        sheet.setColumnWidth(0, 22 * 256);
        sheet.setColumnWidth(1, 44 * 256);
        sheet.setColumnWidth(2, 22 * 256);
        sheet.setColumnWidth(3, 70 * 256);
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
        int totalCols = 28;

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
        // GREEN   0-13: TEST CASE DEFINITION
        // TEAL   14-18: COMPARISON OVERRIDES
        // PURPLE 19-22: AUTOMATION ASSERTIONS
        // RED    23-27: EXECUTION RESULTS
        Row r5 = sheet.createRow(5);
        setCellStyled(r5, 0,  "TEST CASE DEFINITION",   s.tcHeader);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(5, 5, 0, 13));
        setCellStyled(r5, 14, "COMPARISON OVERRIDES",   s.cmpHeader);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(5, 5, 14, 18));
        setCellStyled(r5, 19, "AUTOMATION ASSERTIONS",  s.autoHeader);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(5, 5, 19, 22));
        setCellStyled(r5, 23, "EXECUTION RESULTS",      s.resultHeader);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(5, 5, 23, totalCols - 1));

        // Row 6: column headers (28 cols)
        String[] headers = {
            // GREEN 0-13
            "ID", "Name", "Description", "Enabled", "Verification Mode", "Phase",
            "Method", "Endpoint", "Query Params", "Form Params", "JSON Body",
            "Headers", "Author", "Extract Variables",
            // TEAL 14-18
            "Ignore Fields", "Ignore Array Order", "Compare Error Responses",
            "Numeric Tolerance", "Case Sensitive",
            // PURPLE 19-22
            "Expected Status", "Expected Body (Assertions)", "Expected Headers", "Max Response Time (ms)",
            // RED 23-27
            "Overall Status", "Mode Run", "Comparison Result", "Assertion Result", "Executed At"
        };
        Row r6 = sheet.createRow(6);
        for (int i = 0; i < headers.length; i++) {
            CellStyle cs = i <= 13 ? s.tcHeader : (i <= 18 ? s.cmpHeader : (i <= 22 ? s.autoHeader : s.resultHeader));
            setCellStyled(r6, i, headers[i], cs);
        }

        // Rows 7+: test cases
        int rowIdx = 7;
        for (TestCase tc : group.getTestCases()) {
            Row row = sheet.createRow(rowIdx++);

            // GREEN 0-13
            row.createCell(0).setCellValue(nvl(tc.getId()));
            row.createCell(1).setCellValue(nvl(tc.getName()));
            row.createCell(2).setCellValue(nvl(tc.getDescription()));
            row.createCell(3).setCellValue(String.valueOf(tc.isEnabled()).toUpperCase());
            row.createCell(4).setCellValue(tc.getVerificationMode() != null ? tc.getVerificationMode().getValue() : "comparison");
            row.createCell(5).setCellValue(tc.getPhase() != null ? tc.getPhase().getValue() : "test");
            row.createCell(6).setCellValue(tc.getMethod() != null ? tc.getMethod().name() : "GET");
            row.createCell(7).setCellValue(nvl(tc.getEndpoint()));
            row.createCell(8).setCellValue(tc.getQueryParamsAsString());
            row.createCell(9).setCellValue(tc.getFormParamsAsString());
            row.createCell(10).setCellValue(nvl(tc.getJsonBody()));
            row.createCell(11).setCellValue(nvl(tc.getHeaders()));
            row.createCell(12).setCellValue(nvl(tc.getAuthor()));
            row.createCell(13).setCellValue(nvl(tc.getExtractVariables()));

            // TEAL 14-18
            ComparisonConfig cmp = tc.getComparisonConfig();
            row.createCell(14).setCellValue(cmp != null ? nvl(cmp.getIgnoreFieldsRaw()) : "");
            row.createCell(15).setCellValue(cmp != null ? String.valueOf(cmp.isIgnoreArrayOrder()) : "");
            row.createCell(16).setCellValue(cmp != null ? String.valueOf(cmp.isCompareErrorResponses()).toUpperCase() : "");
            row.createCell(17).setCellValue(cmp != null ? String.valueOf(cmp.getNumericTolerance()) : "");
            row.createCell(18).setCellValue(cmp != null ? String.valueOf(cmp.isCaseSensitive()) : "");

            // PURPLE 19-22
            AutomationConfig auto = tc.getAutomationConfig();
            row.createCell(19).setCellValue(auto != null ? nvl(auto.getExpectedStatus()) : "");
            row.createCell(20).setCellValue(auto != null ? nvl(auto.getExpectedBody()) : "");
            row.createCell(21).setCellValue(auto != null ? nvl(auto.getExpectedHeaders()) : "");
            row.createCell(22).setCellValue(auto != null && auto.getMaxResponseTime() > 0 ? String.valueOf(auto.getMaxResponseTime()) : "");

            // RED 23-27
            TestResult res = tc.getResult();
            row.createCell(23).setCellValue(res != null && res.getStatus() != null ? res.getStatus().name().toLowerCase() : "");
            row.createCell(24).setCellValue(res != null ? nvl(res.getModeRun()) : "");
            row.createCell(25).setCellValue(res != null ? nvl(res.getComparisonResult()) : "");
            row.createCell(26).setCellValue(res != null ? nvl(res.getAssertionResult()) : "");
            row.createCell(27).setCellValue(res != null ? nvl(res.getExecutedAt()) : "");
        }

        // Column widths (28 cols)
        int[] widths = {
            9, 22, 36, 8, 13, 9, 8, 28, 22, 18, 30, 18, 20, 28,   // GREEN 0-13
            16, 15, 16, 13, 12,                                      // TEAL 14-18
            13, 36, 20, 14,                                           // PURPLE 19-22
            12, 13, 36, 36, 18                                        // RED 23-27
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