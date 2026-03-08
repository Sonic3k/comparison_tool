package com.fpt.comparison_tool;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;

public class APIComparisonTemplateGenerator {

    public static void main(String[] args) {
        try {
            generateTemplate();
            System.out.println("✅ Excel template created successfully!");
            System.out.println("📁 File: API_Comparison_Test_Suite_Template.xlsx");
            System.out.println("");
            System.out.println("🎯 Template Features:");
            System.out.println("   ✓ Settings tab with basic suite information and execution config");
            System.out.println("   ✓ Environment Setting tab with flexible source/target configuration");
            System.out.println("   ✓ Auth Profiles tab for reusable authentication configurations");
            System.out.println("   ✓ Multiple TC sheets (TC - User APIs, TC - Auth APIs...) — one sheet per test group");
            System.out.println("   ✓ Each TC sheet has a group info block at the top");
            System.out.println("   ✓ System identifies TC sheets by 'TC-' prefix");
            System.out.println("   ✓ Test Case section (GREEN) and Results section (RED) clearly separated");
        } catch (IOException e) {
            System.err.println("❌ Error creating Excel template: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void generateTemplate() throws IOException {
        Workbook workbook = new XSSFWorkbook();

        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle sectionStyle = createSectionStyle(workbook);

        createSettingsTab(workbook, headerStyle, sectionStyle);
        createEnvironmentSettingTab(workbook, headerStyle, sectionStyle);
        createAuthProfilesTab(workbook, headerStyle);

        // Define test groups — each becomes one TC sheet
        List<TestGroup> groups = buildTestGroups();
        for (TestGroup group : groups) {
            createTestGroupSheet(workbook, group);
        }

        try (FileOutputStream fileOut = new FileOutputStream("API_Comparison_Test_Suite_Template.xlsx")) {
            workbook.write(fileOut);
        }

        workbook.close();
    }

    // ─── Data Model ───────────────────────────────────────────────────────────

    static class TestGroup {
        String name;          // e.g. "User APIs"
        String description;
        String owner;
        List<Object[]> testCases;

        TestGroup(String name, String description, String owner, List<Object[]> testCases) {
            this.name = name;
            this.description = description;
            this.owner = owner;
            this.testCases = testCases;
        }

        String sheetName() {
            return "TC - " + name; // e.g. "TC - User APIs"
        }
    }

    private static List<TestGroup> buildTestGroups() {
        List<TestGroup> groups = new ArrayList<>();

        // TC - User APIs
        // Columns: ID, Name, Description, Enabled, Method, Endpoint, Query Params, Form Params, JSON Body, Headers, Author
        //          | Status, Differences, Source Status, Target Status, Source Response, Target Response, Executed At
        List<Object[]> userTests = new ArrayList<>();
        userTests.add(new Object[]{
            "TC001", "Get User Profile", "Retrieve user profile by user ID",
            "TRUE", "GET", "/api/users/12345",
            "&include=profile&include=preferences", "", "", "", "john.doe@company.com",
            "passed", "", "200", "200",
            "{\"id\": 12345, \"name\": \"John Doe\", \"email\": \"john@example.com\"}",
            "{\"id\": 12345, \"name\": \"John Doe\", \"email\": \"john@example.com\"}",
            "2025-01-15 10:30:15"
        });
        userTests.add(new Object[]{
            "TC002", "Create New User", "Create a new user account with validation",
            "TRUE", "POST", "/api/users",
            "", "", "{\"name\": \"John Doe\", \"email\": \"john.doe@example.com\", \"role\": \"user\"}", "", "jane.smith@company.com",
            "failed", "Response field \"created_at\" format differs: ISO string vs timestamp",
            "201", "201",
            "{\"id\": 67890, \"created_at\": \"2025-01-15T10:30:15Z\"}",
            "{\"id\": 67890, \"created_at\": 1737024615}",
            "2025-01-15 10:30:17"
        });
        userTests.add(new Object[]{
            "TC003", "Update User Email", "Update user email address",
            "TRUE", "PUT", "/api/users/12345/email",
            "", "", "{\"email\": \"newemail@example.com\"}", "", "mike.wilson@company.com",
            "passed", "", "200", "200",
            "{\"id\": 12345, \"email\": \"newemail@example.com\"}",
            "{\"id\": 12345, \"email\": \"newemail@example.com\"}",
            "2025-01-15 10:30:19"
        });
        userTests.add(new Object[]{
            "TC004", "Delete User Account", "Soft delete user account and associated data",
            "TRUE", "DELETE", "/api/users/12345",
            "&reason=user_request", "", "", "", "sarah.jones@company.com",
            "passed", "", "204", "204", "", "", "2025-01-15 10:30:21"
        });
        userTests.add(new Object[]{
            "TC005", "List Users with Pagination", "Get paginated user list with filtering",
            "TRUE", "GET", "/api/users",
            "&page=1&limit=10&status=active", "", "", "", "david.brown@company.com",
            "failed", "Pagination key differs: legacy \"total_count\" vs new \"total\"",
            "200", "200",
            "{\"users\": [...], \"pagination\": {\"total_count\": 150}}",
            "{\"users\": [...], \"pagination\": {\"total\": 150}}",
            "2025-01-15 10:30:23"
        });
        groups.add(new TestGroup("User APIs",
                "User management API tests for v2.0 migration",
                "john.doe@company.com", userTests));

        // TC - Auth APIs
        List<Object[]> authTests = new ArrayList<>();
        authTests.add(new Object[]{
            "TC001", "User Login", "Authenticate user with email and password",
            "TRUE", "POST", "/api/auth/login",
            "", "", "{\"email\": \"user@example.com\", \"password\": \"password123\"}", "", "lisa.garcia@company.com",
            "error", "Target environment returned 500 Internal Server Error",
            "200", "500",
            "{\"token\": \"jwt.token.here\", \"expires_in\": 3600}",
            "{\"error\": \"Internal Server Error\"}",
            "2025-01-15 10:30:25"
        });
        authTests.add(new Object[]{
            "TC002", "User Registration (Form)", "Register new user with form-encoded data",
            "TRUE", "POST", "/api/auth/register",
            "", "username=newuser&email=newuser@example.com&password=password123", "", "", "michelle.thomas@company.com",
            "", "", "", "", "", "", ""
        });
        authTests.add(new Object[]{
            "TC003", "Token Refresh", "Refresh access token using refresh token",
            "TRUE", "POST", "/api/auth/refresh",
            "", "", "{\"refresh_token\": \"refresh.token.here\"}", "", "lisa.garcia@company.com",
            "", "", "", "", "", "", ""
        });
        authTests.add(new Object[]{
            "TC004", "Logout", "Invalidate current user session",
            "TRUE", "POST", "/api/auth/logout",
            "", "", "", "", "lisa.garcia@company.com",
            "", "", "", "", "", "", ""
        });
        groups.add(new TestGroup("Auth APIs",
                "Authentication and session management tests",
                "lisa.garcia@company.com", authTests));

        // TC - Order APIs
        List<Object[]> orderTests = new ArrayList<>();
        orderTests.add(new Object[]{
            "TC001", "Get Order Details", "Retrieve detailed order information",
            "TRUE", "GET", "/api/orders/ORD-12345",
            "&include=items&include=customer&include=shipping", "", "", "", "robert.taylor@company.com",
            "passed", "", "200", "200",
            "{\"orderId\": \"ORD-12345\", \"status\": \"confirmed\"}",
            "{\"orderId\": \"ORD-12345\", \"status\": \"confirmed\"}",
            "2025-01-15 10:30:27"
        });
        orderTests.add(new Object[]{
            "TC002", "List Orders", "Get paginated order list for a user",
            "TRUE", "GET", "/api/orders",
            "&userId=12345&page=1&limit=20", "", "", "", "robert.taylor@company.com",
            "", "", "", "", "", "", ""
        });
        orderTests.add(new Object[]{
            "TC003", "Cancel Order", "Cancel an existing order",
            "TRUE", "PATCH", "/api/orders/ORD-12345/cancel",
            "", "", "{\"reason\": \"customer_request\"}", "", "robert.taylor@company.com",
            "", "", "", "", "", "", ""
        });
        groups.add(new TestGroup("Order APIs",
                "Order management and fulfillment API tests",
                "robert.taylor@company.com", orderTests));

        // TC - Payment APIs
        List<Object[]> paymentTests = new ArrayList<>();
        paymentTests.add(new Object[]{
            "TC001", "Process Payment", "Process payment for an order",
            "FALSE", "POST", "/api/payments",
            "", "", "{\"orderId\": \"ORD-12345\", \"amount\": 99.99, \"currency\": \"USD\"}", "", "anna.martinez@company.com",
            "", "", "", "", "", "", ""
        });
        paymentTests.add(new Object[]{
            "TC002", "Get Payment Status", "Check payment status by payment ID",
            "TRUE", "GET", "/api/payments/PAY-99999",
            "", "", "", "", "anna.martinez@company.com",
            "", "", "", "", "", "", ""
        });
        paymentTests.add(new Object[]{
            "TC003", "Refund Payment", "Process full or partial refund",
            "FALSE", "POST", "/api/payments/PAY-99999/refund",
            "", "", "{\"amount\": 50.00, \"reason\": \"partial_return\"}", "", "anna.martinez@company.com",
            "", "", "", "", "", "", ""
        });
        groups.add(new TestGroup("Payment APIs",
                "Payment processing and refund API tests (disabled by default)",
                "anna.martinez@company.com", paymentTests));

        // TC - Product APIs
        List<Object[]> productTests = new ArrayList<>();
        productTests.add(new Object[]{
            "TC001", "Get Product Catalog", "Retrieve products with filtering and sorting",
            "TRUE", "GET", "/api/products",
            "&category=electronics&limit=50&sort=price", "", "", "", "chris.anderson@company.com",
            "", "", "", "", "", "", ""
        });
        productTests.add(new Object[]{
            "TC002", "Get Product Detail", "Retrieve single product by ID",
            "TRUE", "GET", "/api/products/PROD-555",
            "&include=inventory&include=images", "", "", "", "chris.anderson@company.com",
            "", "", "", "", "", "", ""
        });
        productTests.add(new Object[]{
            "TC003", "Search Products", "Full-text search across product catalog",
            "TRUE", "GET", "/api/products/search",
            "&q=laptop&brand=Dell&minPrice=500&maxPrice=2000", "", "", "", "chris.anderson@company.com",
            "", "", "", "", "", "", ""
        });
        groups.add(new TestGroup("Product APIs",
                "Product catalog and search API tests",
                "chris.anderson@company.com", productTests));

        return groups;
    }

    // ─── TC Sheet Creator ─────────────────────────────────────────────────────

    /**
     * Creates one sheet per TestGroup. Sheet name = "TC - User APIs".
     * The system will scan for sheets with the "TC - " prefix when importing.
     *
     * Layout:
     *   Row 0 : GROUP INFO header (merged)
     *   Row 1 : Group Name
     *   Row 2 : Description
     *   Row 3 : Owner
     *   Row 4 : (blank separator)
     *   Row 5 : Section headers — "TEST CASE DEFINITION" | "EXECUTION RESULTS"
     *   Row 6 : Column headers
     *   Row 7+ : Test case rows
     */
    private static void createTestGroupSheet(Workbook workbook, TestGroup group) {
        Sheet sheet = workbook.createSheet(group.sheetName());

        CellStyle groupInfoHeaderStyle = createGroupInfoHeaderStyle(workbook);
        CellStyle groupInfoLabelStyle = createGroupInfoLabelStyle(workbook);
        CellStyle groupInfoValueStyle = createGroupInfoValueStyle(workbook);
        CellStyle testCaseHeaderStyle = createTestCaseHeaderStyle(workbook);
        CellStyle resultsHeaderStyle  = createResultsHeaderStyle(workbook);

        int totalCols = 18; // 0-10 = TC definition (11 cols), 11-17 = Results (7 cols)

        // ── Row 0: Group Info block header ──────────────────────────────────
        Row row0 = sheet.createRow(0);
        Cell groupHeaderCell = row0.createCell(0);
        groupHeaderCell.setCellValue("GROUP INFO — " + group.sheetName());
        groupHeaderCell.setCellStyle(groupInfoHeaderStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, totalCols - 1));

        // ── Row 1-3: Group metadata ──────────────────────────────────────────
        createGroupInfoRow(sheet, 1, "Group Name",  group.name,
                groupInfoLabelStyle, groupInfoValueStyle, totalCols);
        createGroupInfoRow(sheet, 2, "Description", group.description,
                groupInfoLabelStyle, groupInfoValueStyle, totalCols);
        createGroupInfoRow(sheet, 3, "Owner",       group.owner,
                groupInfoLabelStyle, groupInfoValueStyle, totalCols);

        // ── Row 4: blank separator ───────────────────────────────────────────
        sheet.createRow(4);

        // ── Row 5: Section headers (TEST CASE / RESULTS) ────────────────────
        Row sectionRow = sheet.createRow(5);

        Cell tcSection = sectionRow.createCell(0);
        tcSection.setCellValue("TEST CASE DEFINITION");
        tcSection.setCellStyle(testCaseHeaderStyle);
        sheet.addMergedRegion(new CellRangeAddress(5, 5, 0, 10)); // cols 0-10 (11 cols)

        Cell resSection = sectionRow.createCell(11);
        resSection.setCellValue("EXECUTION RESULTS");
        resSection.setCellStyle(resultsHeaderStyle);
        sheet.addMergedRegion(new CellRangeAddress(5, 5, 11, totalCols - 1)); // cols 11-17 (7 cols)

        // ── Row 6: Column headers ────────────────────────────────────────────
        String[] headers = {
            // Test Case Definition (0-10) — no "Group" col since that's the sheet
            "ID", "Name", "Description", "Enabled", "Method", "Endpoint",
            "Query Params", "Form Params", "JSON Body", "Headers", "Author",
            // Execution Results (11-17)
            "Status", "Differences", "Source Status", "Target Status",
            "Source Response", "Target Response", "Executed At"
        };

        Row headerRow = sheet.createRow(6);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(i <= 10 ? testCaseHeaderStyle : resultsHeaderStyle);
        }

        // ── Rows 7+: Test cases ───────────────────────────────────────────────
        for (int i = 0; i < group.testCases.size(); i++) {
            Row row = sheet.createRow(7 + i);
            Object[] tc = group.testCases.get(i);
            for (int j = 0; j < tc.length; j++) {
                Cell cell = row.createCell(j);
                cell.setCellValue(tc[j].toString());
            }
        }

        // ── Column widths ─────────────────────────────────────────────────────
        int[] colWidths = {
            // TC definition (0-10)
            10, 25, 40, 8, 8, 35, 35, 30, 40, 25, 25,
            // Results (11-17)
            12, 50, 12, 12, 40, 40, 18
        };
        for (int i = 0; i < colWidths.length; i++) {
            sheet.setColumnWidth(i, colWidths[i] * 256);
        }
    }

    private static void createGroupInfoRow(Sheet sheet, int rowIdx,
            String label, String value,
            CellStyle labelStyle, CellStyle valueStyle, int totalCols) {
        Row row = sheet.createRow(rowIdx);
        Cell labelCell = row.createCell(0);
        labelCell.setCellValue(label);
        labelCell.setCellStyle(labelStyle);
        sheet.addMergedRegion(new CellRangeAddress(rowIdx, rowIdx, 0, 1));

        Cell valueCell = row.createCell(2);
        valueCell.setCellValue(value);
        valueCell.setCellStyle(valueStyle);
        sheet.addMergedRegion(new CellRangeAddress(rowIdx, rowIdx, 2, totalCols - 1));
    }

    // ─── Settings Tab (unchanged) ─────────────────────────────────────────────

    private static void createSettingsTab(Workbook workbook, CellStyle headerStyle, CellStyle sectionStyle) {
        Sheet sheet = workbook.createSheet("Settings");

        Object[][] settingsData = {
            {"Section", "Field", "Value", "Description"},
            {"", "", "", ""},
            {"Basic Info", "Suite Name", "User API Regression Tests", "Name of this test suite"},
            {"Basic Info", "Description", "Test suite for User Management APIs during v2.0 migration", "Description of test suite purpose"},
            {"Basic Info", "Version", "1.0", "Version for tracking changes"},
            {"Basic Info", "Created By", "john.doe@company.com", "Suite creator"},
            {"Basic Info", "Created Date", "2025-01-15", "Creation date"},
            {"Basic Info", "Last Updated By", "jane.smith@company.com", "Last person to update this suite"},
            {"Basic Info", "Last Updated Date", "2025-01-20", "Last update date"},
            {"Basic Info", "Execution Time", "", "Total execution time in seconds (filled after execution)"},
            {"", "", "", ""},
            {"Execution", "Mode", "parallel", "Execution mode: parallel or source_first"},
            {"Execution", "Timeout", "30", "Request timeout in seconds"},
            {"Execution", "Parallel Limit", "10", "Maximum concurrent requests"},
            {"Execution", "Delay Between Requests", "100", "Delay between requests in milliseconds"},
            {"Execution", "Retries", "2", "Number of retry attempts on failure"},
            {"", "", "", ""},
            {"Comparison", "Ignore Fields", "timestamp,requestId,executionTime", "Comma-separated list of fields to ignore"},
            {"Comparison", "Case Sensitive", "TRUE", "Whether string comparison is case sensitive"},
            {"Comparison", "Ignore Array Order", "FALSE", "Whether to ignore array element order"},
            {"Comparison", "Numeric Tolerance", "0.001", "Tolerance for numeric value comparison"},
            {"", "", "", ""},
            {"Storage", "Save Responses", "TRUE", "Save full response data (TRUE/FALSE)"},
            {"Storage", "Save Details", "TRUE", "Save detailed comparison results (TRUE/FALSE)"},
            {"Storage", "Response Size Limit", "1048576", "Max response size to save (bytes)"}
        };

        for (int i = 0; i < settingsData.length; i++) {
            Row row = sheet.createRow(i);
            for (int j = 0; j < settingsData[i].length; j++) {
                Cell cell = row.createCell(j);
                cell.setCellValue(settingsData[i][j].toString());
                if (i == 0) cell.setCellStyle(headerStyle);
                else if (j == 0 && !settingsData[i][j].toString().isEmpty()) cell.setCellStyle(sectionStyle);
            }
        }

        sheet.setColumnWidth(0, 18 * 256);
        sheet.setColumnWidth(1, 22 * 256);
        sheet.setColumnWidth(2, 40 * 256);
        sheet.setColumnWidth(3, 50 * 256);
    }

    // ─── Environment Setting Tab (unchanged) ──────────────────────────────────

    private static void createEnvironmentSettingTab(Workbook workbook, CellStyle headerStyle, CellStyle sectionStyle) {
        Sheet sheet = workbook.createSheet("Environment Setting");

        Object[][] envData = {
            {"Configuration", "Source Environment", "Target Environment", "Description"},
            {"Environment URL", "https://api-legacy.company.com", "https://api-new.company.com", "Base URLs"},
            {"Auth Profile", "Legacy-SAML", "Modern-OAuth2", "Auth profile names from Auth Profiles sheet"},
            {"", "", "", ""},
            {"", "", "", ""},
            {"", "", "", ""},
            {"", "", "", ""},
            {"", "", "", ""},
            {"", "", "", ""},
            {"", "", "", ""},
            {"", "", "", ""},
            {"", "", "", ""},
            {"", "", "", ""},
            {"", "", "", ""},
            {"Default Headers", "Source Headers", "Target Headers", "Headers applied to all requests"},
            {"Content-Type", "application/json", "application/json", "Default content type"},
            {"Accept", "application/json", "application/json", "Default accept header"},
            {"User-Agent", "API-Comparison-Tool/1.0", "API-Comparison-Tool/1.0", "Default user agent"},
            {"X-API-Version", "1.0", "2.0", "API version header"},
            {"X-Request-ID", "{{uuid}}", "{{uuid}}", "Request ID (auto-generated)"},
            {"X-Client-Name", "Legacy-Client", "Modern-Client", "Client identification"},
            {"X-Environment", "legacy", "production", "Environment identification"},
        };

        for (int i = 0; i < envData.length; i++) {
            Row row = sheet.createRow(i);
            for (int j = 0; j < envData[i].length; j++) {
                Cell cell = row.createCell(j);
                cell.setCellValue(envData[i][j].toString());
                if (i == 0 || i == 14) cell.setCellStyle(headerStyle);
                else if (j == 0 && !envData[i][j].toString().isEmpty()) cell.setCellStyle(sectionStyle);
            }
        }

        sheet.setColumnWidth(0, 20 * 256);
        sheet.setColumnWidth(1, 35 * 256);
        sheet.setColumnWidth(2, 35 * 256);
        sheet.setColumnWidth(3, 50 * 256);
    }

    // ─── Auth Profiles Tab (unchanged) ────────────────────────────────────────

    private static void createAuthProfilesTab(Workbook workbook, CellStyle headerStyle) {
        Sheet sheet = workbook.createSheet("Auth Profiles");

        String[] headers = {
            "Profile Name", "Auth Type", "Description", "Token URL", "Username", "Password",
            "Client ID", "Client Secret", "Scope", "Entity ID", "Token", "Additional Config"
        };

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        Object[][] authProfiles = {
            {"Legacy-SAML", "saml", "SAML authentication for legacy environment",
                "https://sso.company.com/saml/token", "api-user@company.com", "********",
                "", "", "", "legacy-api-entity", "", ""},
            {"Modern-OAuth2", "client_credentials", "OAuth2 for modern environment",
                "https://auth.company.com/oauth/token", "", "",
                "api-client-v2", "********", "api:read api:write", "", "", ""},
            {"Admin-Basic", "basic", "Basic auth for admin operations",
                "", "admin", "admin123", "", "", "", "", "", ""},
            {"Public-Bearer", "bearer", "Bearer token for public API access",
                "", "", "", "", "", "", "", "public-api-token-12345", ""},
            {"Payment-OAuth2", "client_credentials", "OAuth2 for payment service",
                "https://payment-auth.company.com/oauth/token", "", "",
                "payment-client", "payment-secret", "payment:process", "", "",
                "{\"audience\": \"payment-api\"}"},
            {"No-Auth", "none", "No authentication required",
                "", "", "", "", "", "", "", "", ""}
        };

        for (int i = 0; i < authProfiles.length; i++) {
            Row row = sheet.createRow(i + 1);
            for (int j = 0; j < authProfiles[i].length; j++) {
                Cell cell = row.createCell(j);
                cell.setCellValue(authProfiles[i][j].toString());
            }
        }

        int[] widths = {15, 18, 30, 35, 20, 15, 15, 15, 20, 20, 25, 20};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
    }

    // ─── Cell Styles ──────────────────────────────────────────────────────────

    private static CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        return style;
    }

    private static CellStyle createSectionStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private static CellStyle createGroupInfoHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 13);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    private static CellStyle createGroupInfoLabelStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        return style;
    }

    private static CellStyle createGroupInfoValueStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.LEMON_CHIFFON.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        return style;
    }

    private static CellStyle createTestCaseHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 12);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_GREEN.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    private static CellStyle createResultsHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 12);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_RED.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }
}