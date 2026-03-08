package com.fpt.comparison_tool.service;

import com.fpt.comparison_tool.model.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

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

    // ─── Settings ─────────────────────────────────────────────────────────────

    private SuiteSettings parseSettings(Workbook wb) {
        Sheet sheet = wb.getSheet("Settings");
        if (sheet == null) return new SuiteSettings();

        SuiteSettings s = new SuiteSettings();
        ExecutionConfig exec = new ExecutionConfig();
        ComparisonConfig cmp = new ComparisonConfig();

        for (Row row : sheet) {
            if (row.getRowNum() == 0) continue;
            String field = cell(row, 1);
            String value = cell(row, 2);
            if (field.isEmpty()) continue;

            switch (field) {
                case "Suite Name"             -> s.setSuiteName(value);
                case "Description"            -> s.setDescription(value);
                case "Version"                -> s.setVersion(value);
                case "Created By"             -> s.setCreatedBy(value);
                case "Created Date"           -> s.setCreatedDate(value);
                case "Last Updated By"        -> s.setLastUpdatedBy(value);
                case "Last Updated Date"      -> s.setLastUpdatedDate(value);
                case "Mode"                   -> exec.setMode(parseEnum(ExecutionMode.class, value, ExecutionMode.PARALLEL));
                case "Timeout"                -> exec.setTimeout(parseInt(value, 30));
                case "Parallel Limit"         -> exec.setParallelLimit(parseInt(value, 10));
                case "Delay Between Requests" -> exec.setDelayBetweenRequests(parseInt(value, 100));
                case "Retries"                -> exec.setRetries(parseInt(value, 2));
                case "Source Environment"     -> exec.setSourceEnvironment(value);
                case "Target Environment"     -> exec.setTargetEnvironment(value);
                case "Ignore Fields"          -> cmp.setIgnoreFieldsRaw(value);
                case "Case Sensitive"         -> cmp.setCaseSensitive(Boolean.parseBoolean(value));
                case "Ignore Array Order"     -> cmp.setIgnoreArrayOrder(Boolean.parseBoolean(value));
                case "Numeric Tolerance"      -> cmp.setNumericTolerance(parseDouble(value, 0.001));
            }
        }
        s.setExecutionConfig(exec);
        s.setComparisonConfig(cmp);
        return s;
    }

    // ─── Environments ─────────────────────────────────────────────────────────
    // Sheet name: "Environments" (new) or "Environment Setting" (legacy fallback)
    // Columns: Name | URL | Auth Profile | Headers (encoded "Key:Value, Key2:Value2")

    private List<Environment> parseEnvironments(Workbook wb) {
        Sheet sheet = wb.getSheet("Environments");
        if (sheet == null) sheet = wb.getSheet("Environment Setting");
        if (sheet == null) return new ArrayList<>();

        // Detect format: new table format has "Name" in cell(0,0)
        // Legacy format has "Configuration" in cell(0,0) — skip it, return empty
        Row headerRow = sheet.getRow(0);
        if (headerRow == null) return new ArrayList<>();
        String firstCell = cell(headerRow, 0);
        if (!firstCell.equalsIgnoreCase("Name")) return new ArrayList<>();

        List<Environment> list = new ArrayList<>();
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String name = cell(row, 0);
            if (name.isEmpty()) continue;

            list.add(new Environment(
                    name,
                    cell(row, 1),                        // url
                    cell(row, 2),                        // authProfile
                    parseHeadersEncoded(cell(row, 3))   // headers encoded -> List<Param>
            ));
        }
        return list;
    }

    /** Parse "Key:Value, Key2:Value2" → List<Param> */
    private List<Param> parseHeadersEncoded(String encoded) {
        List<Param> list = new ArrayList<>();
        if (encoded == null || encoded.isBlank()) return list;
        for (String pair : encoded.split(",")) {
            int idx = pair.indexOf(':');
            if (idx > 0) {
                list.add(new Param(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim()));
            }
        }
        return list;
    }

    // ─── Auth Profiles ────────────────────────────────────────────────────────

    private List<AuthProfile> parseAuthProfiles(Sheet sheet) {
        List<AuthProfile> list = new ArrayList<>();
        if (sheet == null) return list;

        for (Row row : sheet) {
            if (row.getRowNum() == 0) continue;
            String name = cell(row, 0);
            if (name.isEmpty()) continue;

            AuthProfile p = new AuthProfile(
                    name,
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

    // ─── Test Group Sheet ─────────────────────────────────────────────────────

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

        for (int r = 8; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null || cell(row, 0).isEmpty()) continue;
            group.addTestCase(parseTestCaseRow(row));
        }
        return group;
    }

    private TestCase parseTestCaseRow(Row row) {
        TestCase tc = new TestCase();
        tc.setId(cell(row, 0));
        tc.setName(cell(row, 1));
        tc.setDescription(cell(row, 2));
        tc.setEnabled(Boolean.parseBoolean(cell(row, 3)));
        tc.setMethod(parseEnum(HttpMethod.class, cell(row, 4), HttpMethod.GET));
        tc.setEndpoint(cell(row, 5));
        tc.setQueryParams(parseParams(cell(row, 6)));
        tc.setFormParams(parseParams(cell(row, 7)));
        tc.setJsonBody(cell(row, 8));
        tc.setHeaders(cell(row, 9));
        tc.setAuthor(cell(row, 10));

        String ignoreFields = cell(row, 11);
        String caseSens     = cell(row, 12);
        String ignoreOrder  = cell(row, 13);
        String numTol       = cell(row, 14);
        if (!ignoreFields.isEmpty() || !caseSens.isEmpty() || !ignoreOrder.isEmpty() || !numTol.isEmpty()) {
            ComparisonConfig cmp = new ComparisonConfig();
            cmp.setIgnoreFieldsRaw(ignoreFields);
            if (!caseSens.isEmpty())    cmp.setCaseSensitive(Boolean.parseBoolean(caseSens));
            if (!ignoreOrder.isEmpty()) cmp.setIgnoreArrayOrder(Boolean.parseBoolean(ignoreOrder));
            if (!numTol.isEmpty())      cmp.setNumericTolerance(parseDouble(numTol, 0.001));
            tc.setComparisonConfig(cmp);
        }

        String status = cell(row, 15);
        if (!status.isEmpty()) {
            TestResult result = new TestResult();
            result.setStatus(parseEnum(ExecutionStatus.class, status.toUpperCase(), ExecutionStatus.PENDING));
            result.setDifferences(cell(row, 16));
            result.setSourceStatus(cell(row, 17));
            result.setTargetStatus(cell(row, 18));
            result.setSourceResponse(cell(row, 19));
            result.setTargetResponse(cell(row, 20));
            result.setExecutedAt(cell(row, 21));
            tc.setResult(result);
        }
        return tc;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

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