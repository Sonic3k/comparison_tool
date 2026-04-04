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
 * TC sheet column layout (0-based):
 *   GREEN  0  ID
 *          1  Name
 *          2  Description
 *          3  Enabled
 *          4  Mode (verification mode)
 *          5  Phase (setup / test / teardown)
 *          6  Method
 *          7  Endpoint
 *          8  Query Params
 *          9  Form Params
 *         10  JSON Body
 *         11  Headers
 *         12  Author
 *         13  Extract Variables
 *   TEAL  14  Ignore Fields
 *         15  Ignore Array Order
 *         16  Compare Error Responses
 *         17  Numeric Tolerance
 *         18  Case Sensitive
 *   PURPLE 19  Expected Status
 *          20  Expected Body
 *          21  Expected Headers
 *          22  Max Response Time
 *   RED   23  Overall Status
 *         24  Mode Run
 *         25  Comparison Result
 *         26  Assertion Result
 *         27  Executed At
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
            list.add(new Environment(name, cell(row, 1), cell(row, 2), parseHeadersEncoded(cell(row, 3))));
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

        // Data starts at row 8 (0-based index 7)
        for (int r = 7; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null || cell(row, 0).isEmpty()) continue;
            group.addTestCase(parseTestCaseRow(row));
        }
        return group;
    }

    private TestCase parseTestCaseRow(Row row) {
        TestCase tc = new TestCase();

        // ── GREEN: cols 0-13 ─────────────────────────────────────────────────
        tc.setId(cell(row, 0));
        tc.setName(cell(row, 1));
        tc.setDescription(cell(row, 2));
        tc.setEnabled(Boolean.parseBoolean(cell(row, 3)));
        tc.setVerificationMode(VerificationMode.from(cell(row, 4)));
        tc.setPhase(Phase.from(cell(row, 5)));
        tc.setMethod(parseEnum(HttpMethod.class, cell(row, 6), HttpMethod.GET));
        tc.setEndpoint(cell(row, 7));
        tc.setQueryParams(parseParams(cell(row, 8)));
        tc.setFormParams(parseParams(cell(row, 9)));
        tc.setJsonBody(cell(row, 10));
        tc.setHeaders(cell(row, 11));
        tc.setAuthor(cell(row, 12));
        tc.setExtractVariables(cell(row, 13));

        // ── TEAL: cols 14-18 ─────────────────────────────────────────────────
        String ignoreFields = cell(row, 14);
        String ignoreOrder  = cell(row, 15);
        String cmpErrors    = cell(row, 16);
        String numTol       = cell(row, 17);
        String caseSens     = cell(row, 18);

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

        // ── PURPLE: cols 19-22 ───────────────────────────────────────────────
        String expStatus  = cell(row, 19);
        String expBody    = cell(row, 20);
        String expHeaders = cell(row, 21);
        String maxRt      = cell(row, 22);

        if (!expStatus.isEmpty() || !expBody.isEmpty() || !expHeaders.isEmpty() || !maxRt.isEmpty()) {
            AutomationConfig auto = new AutomationConfig();
            auto.setExpectedStatus(expStatus);
            auto.setExpectedBody(expBody);
            auto.setExpectedHeaders(expHeaders);
            auto.setMaxResponseTime(parseInt(maxRt, 0));
            tc.setAutomationConfig(auto);
        }

        // ── RED: cols 23-27 ──────────────────────────────────────────────────
        String overallStatus = cell(row, 23);
        if (!overallStatus.isEmpty()) {
            TestResult result = new TestResult();
            result.setStatus(parseEnum(ExecutionStatus.class, overallStatus.toUpperCase(), ExecutionStatus.PENDING));
            result.setModeRun(cell(row, 24));
            result.setComparisonResult(cell(row, 25));
            result.setAssertionResult(cell(row, 26));
            result.setExecutedAt(cell(row, 27));
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
