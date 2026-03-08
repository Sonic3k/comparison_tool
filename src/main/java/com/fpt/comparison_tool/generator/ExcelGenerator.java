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
            { "Execution", "Timeout",               String.valueOf(ec.getTimeout()),              "Request timeout in seconds" },
            { "Execution", "Parallel Limit",        String.valueOf(ec.getParallelLimit()),        "Max concurrent requests" },
            { "Execution", "Delay Between Requests",String.valueOf(ec.getDelayBetweenRequests()), "Delay in milliseconds" },
            { "Execution", "Retries",               String.valueOf(ec.getRetries()),              "Retry attempts on failure" },
            { "Execution", "Source Environment",    nvl(ec.getSourceEnvironment()),              "Name of source environment" },
            { "Execution", "Target Environment",    nvl(ec.getTargetEnvironment()),              "Name of target environment" },
            { "", "", "", "" },
            { "Comparison", "Ignore Fields",      nvl(cc.getIgnoreFieldsRaw()),                              "Comma-separated fields to skip" },
            { "Comparison", "Case Sensitive",     String.valueOf(cc.isCaseSensitive()).toUpperCase(),         "TRUE or FALSE" },
            { "Comparison", "Ignore Array Order", String.valueOf(cc.isIgnoreArrayOrder()).toUpperCase(),      "TRUE or FALSE" },
            { "Comparison", "Numeric Tolerance",  String.valueOf(cc.getNumericTolerance()),                   "Tolerance for numeric comparison" },
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
        int totalCols = 22;

        // Row 0: group info header
        Row r0 = sheet.createRow(0);
        setCellStyled(r0, 0, "GROUP INFO — TC - " + group.getName(), s.groupHeader);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, totalCols - 1));

        // Rows 1-4: metadata (Group Name, Description, Owner, Enabled)
        writeGroupInfoRow(sheet, 1, "Group Name",  group.getName(),                    s);
        writeGroupInfoRow(sheet, 2, "Description", group.getDescription(),             s);
        writeGroupInfoRow(sheet, 3, "Owner",        group.getOwner(),                   s);
        writeGroupInfoRow(sheet, 4, "Enabled",      String.valueOf(group.isEnabled()), s);

        // Row 5: blank
        sheet.createRow(5);

        // Row 6: section banners
        // Cols 0-10: TEST CASE DEFINITION (GREEN)
        // Cols 11-14: COMPARISON OVERRIDE (TEAL)
        // Cols 15-21: EXECUTION RESULTS (RED)
        Row r6 = sheet.createRow(6);
        setCellStyled(r6, 0,  "TEST CASE DEFINITION",  s.tcHeader);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(6, 6, 0, 10));
        setCellStyled(r6, 11, "COMPARISON OVERRIDE",   s.cmpHeader);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(6, 6, 11, 14));
        setCellStyled(r6, 15, "EXECUTION RESULTS",     s.resultHeader);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(6, 6, 15, totalCols - 1));

        // Row 7: column headers (22 cols total)
        String[] headers = {
            // TEST CASE DEFINITION (0-10)
            "ID", "Name", "Description", "Enabled", "Method", "Endpoint",
            "Query Params", "Form Params", "JSON Body", "Headers", "Author",
            // COMPARISON OVERRIDE (11-14)
            "Ignore Fields", "Case Sensitive", "Ignore Array Order", "Numeric Tolerance",
            // EXECUTION RESULTS (15-21)
            "Status", "Differences", "Source Status", "Target Status",
            "Source Response", "Target Response", "Executed At"
        };
        Row r7 = sheet.createRow(7);
        for (int i = 0; i < headers.length; i++) {
            CellStyle cs = i <= 10 ? s.tcHeader : (i <= 14 ? s.cmpHeader : s.resultHeader);
            setCellStyled(r7, i, headers[i], cs);
        }

        // Rows 8+: test cases
        int rowIdx = 8;
        for (TestCase tc : group.getTestCases()) {
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(nvl(tc.getId()));
            row.createCell(1).setCellValue(nvl(tc.getName()));
            row.createCell(2).setCellValue(nvl(tc.getDescription()));
            row.createCell(3).setCellValue(String.valueOf(tc.isEnabled()).toUpperCase());
            row.createCell(4).setCellValue(tc.getMethod() != null ? tc.getMethod().name() : "GET");
            row.createCell(5).setCellValue(nvl(tc.getEndpoint()));
            row.createCell(6).setCellValue(tc.getQueryParamsAsString());
            row.createCell(7).setCellValue(tc.getFormParamsAsString());
            row.createCell(8).setCellValue(nvl(tc.getJsonBody()));
            row.createCell(9).setCellValue(nvl(tc.getHeaders()));
            row.createCell(10).setCellValue(nvl(tc.getAuthor()));

            // Comparison override (cols 11-14)
            ComparisonConfig cmp = tc.getComparisonConfig();
            row.createCell(11).setCellValue(cmp != null ? nvl(cmp.getIgnoreFieldsRaw()) : "");
            row.createCell(12).setCellValue(cmp != null ? String.valueOf(cmp.isCaseSensitive()) : "");
            row.createCell(13).setCellValue(cmp != null ? String.valueOf(cmp.isIgnoreArrayOrder()) : "");
            row.createCell(14).setCellValue(cmp != null ? String.valueOf(cmp.getNumericTolerance()) : "");

            // Results (cols 15-21)
            TestResult res = tc.getResult();
            row.createCell(15).setCellValue(res != null && res.getStatus() != null ? res.getStatus().name().toLowerCase() : "");
            row.createCell(16).setCellValue(res != null ? nvl(res.getDifferences()) : "");
            row.createCell(17).setCellValue(res != null ? nvl(res.getSourceStatus()) : "");
            row.createCell(18).setCellValue(res != null ? nvl(res.getTargetStatus()) : "");
            row.createCell(19).setCellValue(res != null ? nvl(res.getSourceResponse()) : "");
            row.createCell(20).setCellValue(res != null ? nvl(res.getTargetResponse()) : "");
            row.createCell(21).setCellValue(res != null ? nvl(res.getExecutedAt()) : "");
        }

        // Column widths
        int[] widths = { 10, 25, 40, 8, 8, 35, 35, 30, 40, 25, 25, 25, 14, 18, 16, 12, 50, 12, 12, 40, 40, 18 };
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
    }

    private void writeGroupInfoRow(Sheet sheet, int rowIdx, String label, String value, Styles s) {
        Row row = sheet.createRow(rowIdx);
        setCellStyled(row, 0, label, s.groupLabel);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(rowIdx, rowIdx, 0, 1));
        setCellStyled(row, 2, nvl(value), s.groupValue);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(rowIdx, rowIdx, 2, 21));
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
        final CellStyle header, section, groupHeader, groupLabel, groupValue, tcHeader, cmpHeader, resultHeader;

        Styles(Workbook wb) {
            header      = build(wb, IndexedColors.DARK_BLUE,  IndexedColors.WHITE, true,  12, false);
            section     = build(wb, IndexedColors.LIGHT_BLUE, IndexedColors.DARK_BLUE, true, 11, false);
            groupHeader = build(wb, IndexedColors.DARK_BLUE,  IndexedColors.WHITE, true,  13, true);
            groupLabel  = build(wb, IndexedColors.LIGHT_CORNFLOWER_BLUE, IndexedColors.DARK_BLUE, true, 11, false);
            groupValue  = build(wb, IndexedColors.LEMON_CHIFFON, IndexedColors.BLACK, false, 11, false);
            tcHeader    = build(wb, IndexedColors.DARK_GREEN, IndexedColors.WHITE, true, 12, true);
            cmpHeader   = build(wb, IndexedColors.TEAL,       IndexedColors.WHITE, true, 12, true);
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