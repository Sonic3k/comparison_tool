package com.fpt.comparison_tool;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;

import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class APIComparisonTemplateGeneratorBK {
    
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
            System.out.println("   ✓ Test Cases tab with integrated results (2 sections with different colors)");
            System.out.println("   ✓ Form Params column included for form-based requests");
            System.out.println("   ✓ Path variables included in endpoint URLs");
            System.out.println("   ✓ Query params in &param1=value1&param2=value2 format");
            System.out.println("   ✓ No auth override in test cases (uses suite-level auth only)");
            System.out.println("   ✓ Test Case section (GREEN) and Results section (RED) clearly separated");
            System.out.println("   ✓ Simplified results without match status columns");
        } catch (IOException e) {
            System.err.println("❌ Error creating Excel template: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public static void generateTemplate() throws IOException {
        Workbook workbook = new XSSFWorkbook();
        
        // Create styles
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle sectionStyle = createSectionStyle(workbook);
        
        // Create all tabs
        createSettingsTab(workbook, headerStyle, sectionStyle);
        createEnvironmentSettingTab(workbook, headerStyle, sectionStyle);
        createAuthProfilesTab(workbook, headerStyle);
        createTestCasesTab(workbook, headerStyle);
        
        // Write to file
        try (FileOutputStream fileOut = new FileOutputStream("API_Comparison_Test_Suite_Template.xlsx")) {
            workbook.write(fileOut);
        }
        
        workbook.close();
    }
    
    private static void createSettingsTab(Workbook workbook, CellStyle headerStyle, CellStyle sectionStyle) {
        Sheet sheet = workbook.createSheet("Settings");
        
        // Define settings data
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
            {"Comparison", "Ignore Fields", "timestamp,requestId,executionTime", "Comma-separated list of fields to ignore in comparison"},
            {"Comparison", "Case Sensitive", "TRUE", "Whether string comparison is case sensitive"},
            {"Comparison", "Ignore Array Order", "FALSE", "Whether to ignore array element order"},
            {"Comparison", "Numeric Tolerance", "0.001", "Tolerance for numeric value comparison"},
            {"", "", "", ""},
            {"Storage", "Save Responses", "TRUE", "Save full response data (TRUE/FALSE)"},
            {"Storage", "Save Details", "TRUE", "Save detailed comparison results (TRUE/FALSE)"},
            {"Storage", "Response Size Limit", "1048576", "Max response size to save (bytes)"}
        };
        
        // Populate data
        for (int i = 0; i < settingsData.length; i++) {
            Row row = sheet.createRow(i);
            for (int j = 0; j < settingsData[i].length; j++) {
                Cell cell = row.createCell(j);
                cell.setCellValue(settingsData[i][j].toString());
                
                // Apply header style to first row
                if (i == 0) {
                    cell.setCellStyle(headerStyle);
                }
                // Apply section style to section names
                else if (j == 0 && !settingsData[i][j].toString().isEmpty()) {
                    cell.setCellStyle(sectionStyle);
                }
            }
        }
        
        // Set column widths
        sheet.setColumnWidth(0, 18 * 256); // Section
        sheet.setColumnWidth(1, 22 * 256); // Field
        sheet.setColumnWidth(2, 40 * 256); // Value
        sheet.setColumnWidth(3, 50 * 256); // Description
    }
    
    private static void createEnvironmentSettingTab(Workbook workbook, CellStyle headerStyle, CellStyle sectionStyle) {
        Sheet sheet = workbook.createSheet("Environment Setting");
        
        // Define environment and headers data
        Object[][] envHeadersData = {
            {"Configuration", "Source Environment", "Target Environment", "Description"},
            {"Environment URL", "https://api-legacy.company.com", "https://api-new.company.com", "Base URLs for source and target environments"},
            {"Auth Profile", "Legacy-SAML", "Modern-OAuth2", "Authentication profile names from Auth Profiles sheet"},
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
            {"Default Headers", "Source Headers", "Target Headers", "Headers applied to all requests (except auth headers)"},
            {"Content-Type", "application/json", "application/json", "Default content type"},
            {"Accept", "application/json", "application/json", "Default accept header"},
            {"User-Agent", "API-Comparison-Tool/1.0", "API-Comparison-Tool/1.0", "Default user agent"},
            {"X-API-Version", "1.0", "2.0", "API version header"},
            {"X-Request-ID", "{{uuid}}", "{{uuid}}", "Request ID (auto-generated)"},
            {"X-Client-Name", "Legacy-Client", "Modern-Client", "Client identification"},
            {"X-Environment", "legacy", "production", "Environment identification"},
            {"", "", "", ""},
            {"", "", "", ""},
            {"", "", "", ""},
            {"", "", "", ""},
            {"", "", "", ""},
            {"", "", "", ""},
            {"", "", "", ""},
            {"", "", "", ""}
        };
        
        // Populate data
        for (int i = 0; i < envHeadersData.length; i++) {
            Row row = sheet.createRow(i);
            for (int j = 0; j < envHeadersData[i].length; j++) {
                Cell cell = row.createCell(j);
                cell.setCellValue(envHeadersData[i][j].toString());
                
                // Apply header style to first row and row 14 (Default Headers)
                if (i == 0 || i == 14) {
                    cell.setCellStyle(headerStyle);
                }
                // Apply section style to section names
                else if (j == 0 && !envHeadersData[i][j].toString().isEmpty()) {
                    cell.setCellStyle(sectionStyle);
                }
            }
        }
        
        // Set column widths
        sheet.setColumnWidth(0, 20 * 256); // Configuration
        sheet.setColumnWidth(1, 35 * 256); // Source Environment
        sheet.setColumnWidth(2, 35 * 256); // Target Environment
        sheet.setColumnWidth(3, 50 * 256); // Description
    }
    
    private static void createAuthProfilesTab(Workbook workbook, CellStyle headerStyle) {
        Sheet sheet = workbook.createSheet("Auth Profiles");
        
        // Header row
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
        
        // Auth profile data
        Object[][] authProfiles = {
            {
                "Legacy-SAML", "saml", "SAML authentication for legacy environment",
                "https://sso.company.com/saml/token", "api-user@company.com", "********",
                "", "", "", "legacy-api-entity", "", ""
            },
            {
                "Modern-OAuth2", "client_credentials", "OAuth2 client credentials for modern environment",
                "https://auth.company.com/oauth/token", "", "",
                "api-client-v2", "********", "api:read api:write", "", "", ""
            },
            {
                "Admin-Basic", "basic", "Basic authentication for admin operations",
                "", "admin", "admin123",
                "", "", "", "", "", ""
            },
            {
                "Public-Bearer", "bearer", "Bearer token for public API access",
                "", "", "",
                "", "", "", "", "public-api-token-12345", ""
            },
            {
                "Payment-OAuth2", "client_credentials", "OAuth2 for payment service",
                "https://payment-auth.company.com/oauth/token", "", "",
                "payment-client", "payment-secret", "payment:process", "", "", "{\"audience\": \"payment-api\"}"
            },
            {
                "No-Auth", "none", "No authentication required",
                "", "", "",
                "", "", "", "", "", ""
            }
        };
        
        // Populate auth profile data
        for (int i = 0; i < authProfiles.length; i++) {
            Row row = sheet.createRow(i + 1);
            for (int j = 0; j < authProfiles[i].length; j++) {
                Cell cell = row.createCell(j);
                cell.setCellValue(authProfiles[i][j].toString());
            }
        }
        
        // Set column widths
        int[] columnWidths = {15, 18, 30, 35, 20, 15, 15, 15, 20, 20, 25, 20};
        for (int i = 0; i < columnWidths.length; i++) {
            sheet.setColumnWidth(i, columnWidths[i] * 256);
        }
    }
    
    private static void createTestCasesTab(Workbook workbook, CellStyle headerStyle) {
        Sheet sheet = workbook.createSheet("Test Cases");
        
        // Create styles for different sections
        CellStyle testCaseHeaderStyle = createTestCaseHeaderStyle(workbook);
        CellStyle resultsHeaderStyle = createResultsHeaderStyle(workbook);
        
        // Row 0: Merged headers for sections
        Row sectionRow = sheet.createRow(0);
        Cell testCaseSection = sectionRow.createCell(0);
        testCaseSection.setCellValue("TEST CASE DEFINITION");
        testCaseSection.setCellStyle(testCaseHeaderStyle);
        
        Cell resultsSection = sectionRow.createCell(12);
        resultsSection.setCellValue("EXECUTION RESULTS");
        resultsSection.setCellStyle(resultsHeaderStyle);
        
        // Merge cells for section headers
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 11)); // Test Case section (12 columns: 0-11)
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 12, 18)); // Results section (7 columns: 12-18)
        
        // Row 1: Column headers
        String[] headers = {
            // Test Case Definition (columns 0-11)
            "ID", "Group", "Name", "Description", "Enabled", "Method", "Endpoint", 
            "Query Params", "Form Params", "JSON Body", "Headers", "Author",
            // Execution Results (columns 12-18)
            "Status", "Differences", "Source Status", "Target Status", 
            "Source Response", "Target Response", "Executed At"
        };
        
        Row headerRow = sheet.createRow(1);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            
            // Apply different header styles based on section
            if (i <= 11) {
                cell.setCellStyle(testCaseHeaderStyle);
            } else {
                cell.setCellStyle(resultsHeaderStyle);
            }
        }
        
        // Test case data with integrated results
        Object[][] testCases = {
            {
                // Test Case Definition (ID, Group, Name, Description, Enabled, Method, Endpoint, Query Params, Form Params, JSON Body, Headers, Author)
                "TC001", "User APIs", "Get User Profile", "Retrieve user profile information by user ID",
                "TRUE", "GET", "/api/users/12345",
                "&include=profile&include=preferences", "", "", "", "john.doe@company.com",
                // Execution Results
                "passed", "", "200", "200",
                "{\"id\": 12345, \"name\": \"John Doe\", \"email\": \"john@example.com\"}", 
                "{\"id\": 12345, \"name\": \"John Doe\", \"email\": \"john@example.com\"}", 
                "2025-01-15 10:30:15"
            },
            {
                // Test Case Definition
                "TC002", "User APIs", "Create New User", "Create a new user account with validation",
                "TRUE", "POST", "/api/users",
                "", "", "{\"name\": \"John Doe\", \"email\": \"john.doe@example.com\", \"role\": \"user\"}", 
                "", "jane.smith@company.com",
                // Execution Results
                "failed", "Response field \"created_at\" format differs: ISO string vs timestamp", 
                "201", "201",
                "{\"id\": 67890, \"name\": \"John Doe\", \"created_at\": \"2025-01-15T10:30:15Z\"}", 
                "{\"id\": 67890, \"name\": \"John Doe\", \"created_at\": 1737024615}", 
                "2025-01-15 10:30:17"
            },
            {
                // Test Case Definition
                "TC003", "User APIs", "Update User Email", "Update user email address with validation",
                "TRUE", "PUT", "/api/users/12345/email",
                "", "", "{\"email\": \"newemail@example.com\"}", "", "mike.wilson@company.com",
                // Execution Results
                "passed", "", "200", "200",
                "{\"id\": 12345, \"email\": \"newemail@example.com\"}", 
                "{\"id\": 12345, \"email\": \"newemail@example.com\"}", 
                "2025-01-15 10:30:19"
            },
            {
                // Test Case Definition
                "TC004", "User APIs", "Delete User Account", "Soft delete user account and associated data",
                "TRUE", "DELETE", "/api/users/12345",
                "&reason=user_request", "", "", "", "sarah.jones@company.com",
                // Execution Results
                "passed", "", "204", "204", "", "", 
                "2025-01-15 10:30:21"
            },
            {
                // Test Case Definition
                "TC005", "User APIs", "List Users with Pagination", "Get paginated list of users with filtering",
                "TRUE", "GET", "/api/users",
                "&page=1&limit=10&status=active", "", "", "", "david.brown@company.com",
                // Execution Results
                "failed", "Pagination structure differs: legacy uses \"total_count\", new uses \"total\"", 
                "200", "200",
                "{\"users\": [...], \"pagination\": {\"total_count\": 150}}", 
                "{\"users\": [...], \"pagination\": {\"total\": 150}}", 
                "2025-01-15 10:30:23"
            },
            {
                // Test Case Definition
                "TC006", "Auth APIs", "User Login", "Authenticate user with email and password",
                "TRUE", "POST", "/api/auth/login",
                "", "", "{\"email\": \"user@example.com\", \"password\": \"password123\"}", "", "lisa.garcia@company.com",
                // Execution Results
                "error", "Target environment returned 500 Internal Server Error", 
                "200", "500",
                "{\"token\": \"jwt.token.here\", \"expires_in\": 3600}", 
                "{\"error\": \"Internal Server Error\"}", 
                "2025-01-15 10:30:25"
            },
            {
                // Test Case Definition
                "TC007", "Order APIs", "Get Order Details", "Retrieve detailed order information",
                "TRUE", "GET", "/api/orders/ORD-12345",
                "&include=items&include=customer&include=shipping", "", "", "", "robert.taylor@company.com",
                // Execution Results
                "passed", "", "200", "200",
                "{\"orderId\": \"ORD-12345\", \"status\": \"confirmed\"}", 
                "{\"orderId\": \"ORD-12345\", \"status\": \"confirmed\"}", 
                "2025-01-15 10:30:27"
            },
            {
                // Test Case Definition
                "TC008", "Payment APIs", "Process Payment", "Process payment for an order",
                "FALSE", "POST", "/api/payments",
                "", "", "{\"orderId\": \"ORD-12345\", \"amount\": 99.99, \"currency\": \"USD\"}", 
                "", "anna.martinez@company.com",
                // Execution Results
                "", "", "", "", "", "", ""
            },
            {
                // Test Case Definition
                "TC009", "Product APIs", "Get Product Catalog", "Retrieve product catalog with filtering",
                "TRUE", "GET", "/api/products",
                "&category=electronics&limit=50&sort=price", "", "", "", "chris.anderson@company.com",
                // Execution Results
                "", "", "", "", "", "", ""
            },
            {
                // Test Case Definition
                "TC010", "Auth APIs", "User Registration", "Register new user with form data",
                "TRUE", "POST", "/api/auth/register",
                "", "username=newuser&email=newuser@example.com&password=password123&confirm_password=password123", "", "", "michelle.thomas@company.com",
                // Execution Results
                "", "", "", "", "", "", ""
            }
        };
        
        // Populate test case data
        for (int i = 0; i < testCases.length; i++) {
            Row row = sheet.createRow(i + 2); // Start from row 2 (after headers)
            for (int j = 0; j < testCases[i].length; j++) {
                Cell cell = row.createCell(j);
                cell.setCellValue(testCases[i][j].toString());
            }
        }
        
        // Set column widths
        int[] columnWidths = {
            // Test Case Definition columns (0-11): ID, Group, Name, Description, Enabled, Method, Endpoint, Query Params, Form Params, JSON Body, Headers, Author
            8, 15, 25, 40, 8, 8, 35, 35, 30, 40, 25, 25,
            // Execution Results columns (12-18): Status, Differences, Source Status, Target Status, Source Response, Target Response, Executed At
            12, 50, 12, 12, 40, 40, 18
        };
        for (int i = 0; i < columnWidths.length; i++) {
            sheet.setColumnWidth(i, columnWidths[i] * 256);
        }
    }
    
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