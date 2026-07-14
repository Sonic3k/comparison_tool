package com.fpt.comparison_tool.service;

import com.fpt.comparison_tool.model.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses an Excel workbook into a TestSuite.
 *
 * Current TC sheet column layout — 29 columns (0-based):
 *   GREEN  0  ID
 *          1  Test Case ID   ← logical test case this request belongs to
 *          2  Name
 *          3  Description
 *          4  Enabled
 *          5  Mode (verification mode)
 *          6  Phase (setup / test / teardown)
 *          7  Method
 *          8  Endpoint
 *          9  Query Params
 *         10  Form Params
 *         11  JSON Body
 *         12  Headers
 *         13  Author
 *         14  Extract Variables
 *   TEAL  15  Ignore Fields
 *         16  Ignore Array Order
 *         17  Compare Error Responses
 *         18  Numeric Tolerance
 *         19  Case Sensitive
 *   PURPLE 20  Expected Status
 *          21  Expected Body
 *          22  Expected Headers
 *          23  Max Response Time
 *   RED   24  Overall Status
 *         25  Mode Run
 *         26  Comparison Result
 *         27  Assertion Result
 *         28  Executed At
 *         29  Response Time (ms) — "95" or "src 120 · tgt 95"; absent in older files
 *
 * Legacy 28-column workbooks (no "Test Case ID" column) are still accepted:
 * the layout is auto-detected from the header row (row index 6, column 1) and
 * each request defaults to its own test case (testCaseId = ID) on normalize.
 */
@Service
public class ExcelImportService {

    private static final String TC_PREFIX = "TC - ";

    public TestSuite importFrom(InputStream in) throws Exception {
        try (Workbook wb = new XSSFWorkbook(in)) {
            TestSuite suite = new TestSuite();
            suite.setSettings(parseSettings(wb));
            suite.setEnvironments(parseEnvironments(wb));
            suite.setAuthProfiles(parseAuthProfiles(wb.getSheet("Auth Profiles")));
            suite.setGlobalVariables(parseGlobalVariables(wb.getSheet("Variables")));

            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                if (sheet.getSheetName().startsWith(TC_PREFIX)) {
                    suite.addTestGroup(parseTestGroup(sheet));
                }
            }
            return suite;
        }
    }

    // ── Settings ───────────────────────────────────────────────────────────────

    private SuiteSettings parseSettings(Workbook wb) {
        Sheet sheet = wb.getSheet("Settings");
        if (sheet == null) return new SuiteSettings();

        SuiteSettings s    = new SuiteSettings();
        ExecutionConfig ec = new ExecutionConfig();
        ComparisonConfig cc= new ComparisonConfig();

        for (Row row : sheet) {
            if (row.getRowNum() == 0) continue;
            String field = cell(row, 1);
            String value = cell(row, 2);
            if (field.isEmpty()) continue;

            switch (field) {
                case "Suite Name"              -> s.setSuiteName(value);
                case "Description"             -> s.setDescription(value);
                case "Version"                 -> s.setVersion(value);
                case "Created By"              -> s.setCreatedBy(value);
                case "Created Date"            -> s.setCreatedDate(value);
                case "Last Updated By"         -> s.setLastUpdatedBy(value);
                case "Last Updated Date"       -> s.setLastUpdatedDate(value);
                case "Mode"                    -> ec.setMode(parseEnum(ExecutionMode.class, value, ExecutionMode.PARALLEL));
                case "Timeout"                 -> ec.setTimeout(parseInt(value, 30));
                case "Parallel Limit"          -> ec.setParallelLimit(parseInt(value, 10));
                case "Delay Between Requests"  -> ec.setDelayBetweenRequests(parseInt(value, 100));
                case "Retries"                 -> ec.setRetries(parseInt(value, 2));
                case "Source Environment"      -> ec.setSourceEnvironment(value);
                case "Target Environment"      -> ec.setTargetEnvironment(value);
                case "Verification Mode"       -> ec.setVerificationMode(value.isBlank() ? null : VerificationMode.from(value));
                case "Ignore Fields"           -> cc.setIgnoreFieldsRaw(value);
                case "Case Sensitive"          -> cc.setCaseSensitive(Boolean.parseBoolean(value));
                case "Ignore Array Order"      -> cc.setIgnoreArrayOrder(Boolean.parseBoolean(value));
                case "Numeric Tolerance"       -> cc.setNumericTolerance(parseDouble(value, 0.001));
                case "Compare Error Responses" -> cc.setCompareErrorResponses(Boolean.parseBoolean(value));
            }
        }
        s.setExecutionConfig(ec);
        s.setComparisonConfig(cc);
        return s;
    }

    // ── Environments ───────────────────────────────────────────────────────────

    private List<Environment> parseEnvironments(Workbook wb) {
        Sheet sheet = wb.getSheet("Environments");
        if (sheet == null) sheet = wb.getSheet("Environment Setting");
        if (sheet == null) return new ArrayList<>();

        Row headerRow = sheet.getRow(0);
        if (headerRow == null) return new ArrayList<>();
        if (!cell(headerRow, 0).equalsIgnoreCase("Name")) return new ArrayList<>();

        List<Environment> list = new ArrayList<>();
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String name = cell(row, 0);
            if (name.isEmpty()) continue;
            Environment env = new Environment(name, cell(row, 1), cell(row, 2), parseHeadersEncoded(cell(row, 3)));
            env.setVariables(parseVarsEncoded(cell(row, 4)));
            list.add(env);
        }
        return list;
    }

    /** "Variables" sheet: Name | Value | Updated At. Old workbooks lack it. */
    private List<GlobalVariable> parseGlobalVariables(Sheet sheet) {
        List<GlobalVariable> list = new ArrayList<>();
        if (sheet == null) return list;
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String name = cell(row, 0);
            if (name.isEmpty()) continue;
            list.add(new GlobalVariable(name, cell(row, 1), cell(row, 2)));
        }
        return list;
    }

    /** "key=value" per line (commas also accepted) → List<Param>. */
    private List<Param> parseVarsEncoded(String encoded) {
        List<Param> list = new ArrayList<>();
        if (encoded == null || encoded.isBlank()) return list;
        for (String pair : encoded.split("\\r?\\n|,")) {
            int idx = pair.indexOf('=');
            if (idx > 0) list.add(new Param(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim()));
        }
        return list;
    }

    private List<Param> parseHeadersEncoded(String encoded) {
        List<Param> list = new ArrayList<>();
        if (encoded == null || encoded.isBlank()) return list;
        for (String pair : encoded.split(",")) {
            int idx = pair.indexOf(':');
            if (idx > 0) list.add(new Param(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim()));
        }
        return list;
    }

    // ── Auth Profiles ──────────────────────────────────────────────────────────

    private List<AuthProfile> parseAuthProfiles(Sheet sheet) {
        List<AuthProfile> list = new ArrayList<>();
        if (sheet == null) return list;

        for (Row row : sheet) {
            if (row.getRowNum() == 0) continue;
            String name = cell(row, 0);
            if (name.isEmpty()) continue;

            AuthProfile p = new AuthProfile(name,
                    parseEnum(AuthType.class, cell(row, 1).toUpperCase(), AuthType.NONE),
                    cell(row, 2));
            p.setTokenUrl(cell(row, 3));
            p.setUsername(cell(row, 4));
            p.setPassword(cell(row, 5));
            p.setClientId(cell(row, 6));
            p.setClientSecret(cell(row, 7));
            p.setScope(cell(row, 8));
            p.setEntityId(cell(row, 9));
            p.setToken(cell(row, 10));
            p.setAdditionalConfig(cell(row, 11));
            list.add(p);
        }
        return list;
    }

    // ── TC Sheet ───────────────────────────────────────────────────────────────

    private TestGroup parseTestGroup(Sheet sheet) {
        TestGroup group = new TestGroup();

        for (int r = 1; r <= 4; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String label = cell(row, 0);
            String value = cell(row, 2);
            switch (label) {
                case "Group Name"  -> group.setName(value);
                case "Description" -> group.setDescription(value);
                case "Owner"       -> group.setOwner(value);
                case "Enabled"     -> group.setEnabled(!"false".equalsIgnoreCase(value));
            }
        }

        // Layout detection: new 29-col sheets have "Test Case ID" as the
        // second header (row index 6, col 1); legacy 28-col sheets have "Name".
        Row headerRow = sheet.getRow(6);
        boolean newLayout = headerRow != null
                && "test case id".equalsIgnoreCase(cell(headerRow, 1));
        // 31-col layout adds "Auth Profile" after Extract Variables (col 15
        // when Test Case ID is present); older 28/29/30-col sheets lack it.
        boolean hasAuthProfile = headerRow != null
                && "Auth Profile".equalsIgnoreCase(cell(headerRow, 14 + (newLayout ? 1 : 0)));

        // Data starts at row 8 (0-based index 7)
        for (int r = 7; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null || cell(row, 0).isEmpty()) continue;
            group.addTestRequest(parseTestRequestRow(row, newLayout, hasAuthProfile));
        }
        group.normalize();
        return group;
    }

    private TestRequest parseTestRequestRow(Row row, boolean newLayout, boolean hasAuthProfile) {
        TestRequest tc = new TestRequest();

        // Offsets: "Test Case ID" at column 1 shifts everything after ID by +1;
        // "Auth Profile" after Extract Variables shifts TEAL and later by +1 more.
        int o = newLayout ? 1 : 0;
        int a = hasAuthProfile ? 1 : 0;

        // ── GREEN ────────────────────────────────────────────────────────────
        tc.setId(cell(row, 0));
        if (newLayout) {
            String tcId = cell(row, 1);
            tc.setTestCaseId(tcId.isEmpty() ? null : tcId);   // null → normalize() defaults to own id
        }
        tc.setName(cell(row, 1 + o));
        tc.setDescription(cell(row, 2 + o));
        tc.setEnabled(Boolean.parseBoolean(cell(row, 3 + o)));
        tc.setVerificationMode(VerificationMode.from(cell(row, 4 + o)));
        tc.setPhase(Phase.from(cell(row, 5 + o)));
        tc.setMethod(parseEnum(HttpMethod.class, cell(row, 6 + o), HttpMethod.GET));
        tc.setEndpoint(cell(row, 7 + o));
        tc.setQueryParams(parseParams(cell(row, 8 + o)));
        tc.setFormParams(parseParams(cell(row, 9 + o)));
        tc.setJsonBody(cell(row, 10 + o));
        tc.setHeaders(cell(row, 11 + o));
        tc.setAuthor(cell(row, 12 + o));
        tc.setExtractVariables(cell(row, 13 + o));
        if (hasAuthProfile) {
            String ap = cell(row, 14 + o);
            tc.setAuthProfile(ap.isEmpty() ? null : ap);
        }

        // ── TEAL ─────────────────────────────────────────────────────────────
        String ignoreFields = cell(row, 14 + o + a);
        String ignoreOrder  = cell(row, 15 + o + a);
        String cmpErrors    = cell(row, 16 + o + a);
        String numTol       = cell(row, 17 + o + a);
        String caseSens     = cell(row, 18 + o + a);

        if (!ignoreFields.isEmpty() || !ignoreOrder.isEmpty() || !cmpErrors.isEmpty()
                || !numTol.isEmpty() || !caseSens.isEmpty()) {
            ComparisonConfig cmp = new ComparisonConfig();
            cmp.setIgnoreFieldsRaw(ignoreFields);
            if (!ignoreOrder.isEmpty()) cmp.setIgnoreArrayOrder(Boolean.parseBoolean(ignoreOrder));
            if (!cmpErrors.isEmpty())   cmp.setCompareErrorResponses(Boolean.parseBoolean(cmpErrors));
            if (!numTol.isEmpty())      cmp.setNumericTolerance(parseDouble(numTol, 0.001));
            if (!caseSens.isEmpty())    cmp.setCaseSensitive(Boolean.parseBoolean(caseSens));
            tc.setComparisonConfig(cmp);
        }

        // ── PURPLE ───────────────────────────────────────────────────────────
        String expStatus  = cell(row, 19 + o + a);
        String expBody    = cell(row, 20 + o + a);
        String expHeaders = cell(row, 21 + o + a);
        String maxRt      = cell(row, 22 + o + a);

        if (!expStatus.isEmpty() || !expBody.isEmpty() || !expHeaders.isEmpty() || !maxRt.isEmpty()) {
            AutomationConfig auto = new AutomationConfig();
            auto.setExpectedStatus(expStatus);
            auto.setExpectedBody(expBody);
            auto.setExpectedHeaders(expHeaders);
            auto.setMaxResponseTime(parseInt(maxRt, 0));
            tc.setAutomationConfig(auto);
        }

        // ── RED ──────────────────────────────────────────────────────────────
        String overallStatus = cell(row, 23 + o + a);
        if (!overallStatus.isEmpty()) {
            TestResult result = new TestResult();
            result.setStatus(parseEnum(ExecutionStatus.class, overallStatus.toUpperCase(), ExecutionStatus.PENDING));
            result.setModeRun(cell(row, 24 + o + a));
            result.setComparisonResult(cell(row, 25 + o + a));
            result.setAssertionResult(cell(row, 26 + o + a));
            result.setExecutedAt(cell(row, 27 + o + a));
            parseResponseTimes(cell(row, 28 + o + a), result);   // column may be absent in older files
            tc.setResult(result);
        }

        return tc;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private List<Param> parseParams(String raw) {
        List<Param> params = new ArrayList<>();
        if (raw == null || raw.isBlank()) return params;
        for (String part : raw.split("&")) {
            part = part.trim();
            if (!part.isEmpty()) params.add(Param.of(part));
        }
        return params;
    }

    /** Accepts "95", "src 120 · tgt 95", or "src 120"; anything else is ignored. */
    private void parseResponseTimes(String raw, TestResult result) {
        if (raw == null || raw.isBlank()) return;
        String v = raw.trim();
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("src\\s*(\\d+)").matcher(v);
            if (m.find()) result.setSourceTimeMs(Long.parseLong(m.group(1)));
            m = java.util.regex.Pattern.compile("tgt\\s*(\\d+)").matcher(v);
            if (m.find()) result.setTargetTimeMs(Long.parseLong(m.group(1)));
            else if (v.matches("\\d+")) result.setTargetTimeMs(Long.parseLong(v));
        } catch (NumberFormatException ignored) { }
    }

    private String cell(Row row, int col) {
        Cell c = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (c == null) return "";
        return switch (c.getCellType()) {
            case NUMERIC -> String.valueOf((long) c.getNumericCellValue());
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            default      -> c.getStringCellValue().trim();
        };
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        try { return Enum.valueOf(type, value.toUpperCase().replace("-", "_")); }
        catch (Exception e) { return fallback; }
    }

    private int parseInt(String v, int fallback) {
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return fallback; }
    }

    private double parseDouble(String v, double fallback) {
        try { return Double.parseDouble(v.trim()); } catch (Exception e) { return fallback; }
    }
}
