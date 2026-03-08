package com.fpt.comparison_tool.generator;

import com.fpt.comparison_tool.model.*;

import java.util.Arrays;
import java.util.List;

public class SampleDataBuilder {

    public static TestSuite build() {
        TestSuite suite = new TestSuite();
        suite.setSettings(buildSettings());
        suite.setEnvironments(buildEnvironments());
        suite.setAuthProfiles(buildAuthProfiles());
        suite.getTestGroups().addAll(buildTestGroups());
        return suite;
    }

    // ─── Settings ─────────────────────────────────────────────────────────────

    private static SuiteSettings buildSettings() {
        ExecutionConfig exec = new ExecutionConfig(
                ExecutionMode.PARALLEL, 30, 10, 100, 2, "Legacy SIT", "New Dev");

        ComparisonConfig cmp = new ComparisonConfig(
                Arrays.asList("timestamp", "requestId", "executionTime"),
                true, false, 0.001);

        return new SuiteSettings(
                "User API Regression Tests",
                "Test suite for User Management APIs during v2.0 migration",
                "1.0",
                "john.doe@company.com", "2025-01-15",
                "jane.smith@company.com", "2025-01-20",
                exec, cmp);
    }

    // ─── Environments ─────────────────────────────────────────────────────────

    private static List<Environment> buildEnvironments() {
        return Arrays.asList(
            new Environment("Legacy SIT", "https://api-legacy-sit.company.com", "Legacy-SAML",
                Arrays.asList(new Param("Content-Type", "application/json"),
                              new Param("Accept", "application/json"),
                              new Param("X-API-Version", "1.0"),
                              new Param("X-Environment", "legacy-sit"))),
            new Environment("Legacy UAT", "https://api-legacy-uat.company.com", "Legacy-SAML",
                Arrays.asList(new Param("Content-Type", "application/json"),
                              new Param("Accept", "application/json"),
                              new Param("X-API-Version", "1.0"),
                              new Param("X-Environment", "legacy-uat"))),
            new Environment("New Dev", "https://api-new-dev.company.com", "Modern-OAuth2",
                Arrays.asList(new Param("Content-Type", "application/json"),
                              new Param("Accept", "application/json"),
                              new Param("X-API-Version", "2.0"),
                              new Param("X-Environment", "dev"))),
            new Environment("New UAT", "https://api-new-uat.company.com", "Modern-OAuth2",
                Arrays.asList(new Param("Content-Type", "application/json"),
                              new Param("Accept", "application/json"),
                              new Param("X-API-Version", "2.0"),
                              new Param("X-Environment", "uat")))
        );
    }

    // ─── Auth Profiles ────────────────────────────────────────────────────────

    private static List<AuthProfile> buildAuthProfiles() {
        return Arrays.asList(
            new AuthProfile("Legacy-SAML", AuthType.SAML, "SAML authentication for legacy environment")
                .withTokenUrl("https://sso.company.com/saml/token")
                .withUsername("api-user@company.com")
                .withEntityId("legacy-api-entity"),
            new AuthProfile("Modern-OAuth2", AuthType.CLIENT_CREDENTIALS, "OAuth2 for modern environment")
                .withTokenUrl("https://auth.company.com/oauth/token")
                .withClientId("api-client-v2")
                .withClientSecret("********")
                .withScope("api:read api:write"),
            new AuthProfile("Admin-Basic", AuthType.BASIC, "Basic auth for admin operations")
                .withUsername("admin").withPassword("admin123"),
            new AuthProfile("Public-Bearer", AuthType.BEARER, "Bearer token for public API")
                .withToken("public-api-token-12345"),
            new AuthProfile("No-Auth", AuthType.NONE, "No authentication required")
        );
    }

    // ─── Test Groups ──────────────────────────────────────────────────────────

    private static List<TestGroup> buildTestGroups() {
        return Arrays.asList(buildUserGroup(), buildAuthGroup(), buildOrderGroup(), buildPaymentGroup());
    }

    private static TestGroup buildUserGroup() {
        TestGroup g = new TestGroup("User APIs",
                "User management API tests for v2.0 migration", "john.doe@company.com");

        g.addTestCase(tc("TC001", "Get User Profile", "Retrieve user profile by user ID",
                true, HttpMethod.GET, "/api/users/12345", "john.doe@company.com")
            .withQueryParams(Arrays.asList(new Param("include", "profile"), new Param("include", "preferences")))
            .withResult(result(ExecutionStatus.PASSED, "",  "200", "200",
                "{\"id\":12345,\"name\":\"John Doe\",\"email\":\"john@example.com\"}",
                "{\"id\":12345,\"name\":\"John Doe\",\"email\":\"john@example.com\"}",
                "2025-01-15 10:30:15")));

        g.addTestCase(tc("TC002", "Create New User", "Create a new user account with validation",
                true, HttpMethod.POST, "/api/users", "jane.smith@company.com")
            .withJsonBody("{\"name\":\"John Doe\",\"email\":\"john.doe@example.com\",\"role\":\"user\"}")
            .withResult(result(ExecutionStatus.FAILED,
                "Response field \"created_at\" format differs: ISO string vs timestamp",
                "201", "201",
                "{\"id\":67890,\"created_at\":\"2025-01-15T10:30:15Z\"}",
                "{\"id\":67890,\"created_at\":1737024615}",
                "2025-01-15 10:30:17")));

        g.addTestCase(tc("TC003", "Update User Email", "Update user email address",
                true, HttpMethod.PUT, "/api/users/12345/email", "mike.wilson@company.com")
            .withJsonBody("{\"email\":\"newemail@example.com\"}")
            .withResult(result(ExecutionStatus.PASSED, "", "200", "200",
                "{\"id\":12345,\"email\":\"newemail@example.com\"}",
                "{\"id\":12345,\"email\":\"newemail@example.com\"}",
                "2025-01-15 10:30:19")));

        g.addTestCase(tc("TC004", "Delete User Account", "Soft delete user account",
                true, HttpMethod.DELETE, "/api/users/12345", "sarah.jones@company.com")
            .withQueryParams(Arrays.asList(new Param("reason", "user_request")))
            .withResult(result(ExecutionStatus.PASSED, "", "204", "204", "", "", "2025-01-15 10:30:21")));

        g.addTestCase(tc("TC005", "List Users with Pagination", "Get paginated user list",
                true, HttpMethod.GET, "/api/users", "david.brown@company.com")
            .withQueryParams(Arrays.asList(
                new Param("page", "1"), new Param("limit", "10"), new Param("status", "active")))
            .withResult(result(ExecutionStatus.FAILED,
                "Pagination key differs: legacy \"total_count\" vs new \"total\"",
                "200", "200",
                "{\"users\":[],\"pagination\":{\"total_count\":150}}",
                "{\"users\":[],\"pagination\":{\"total\":150}}",
                "2025-01-15 10:30:23")));

        return g;
    }

    private static TestGroup buildAuthGroup() {
        TestGroup g = new TestGroup("Auth APIs",
                "Authentication and session management tests", "lisa.garcia@company.com");

        g.addTestCase(tc("TC001", "User Login", "Authenticate user with email and password",
                true, HttpMethod.POST, "/api/auth/login", "lisa.garcia@company.com")
            .withJsonBody("{\"email\":\"user@example.com\",\"password\":\"password123\"}")
            .withResult(result(ExecutionStatus.ERROR,
                "Target environment returned 500 Internal Server Error",
                "200", "500",
                "{\"token\":\"jwt.token.here\",\"expires_in\":3600}",
                "{\"error\":\"Internal Server Error\"}",
                "2025-01-15 10:30:25")));

        g.addTestCase(tc("TC002", "Token Refresh", "Refresh access token",
                true, HttpMethod.POST, "/api/auth/refresh", "lisa.garcia@company.com")
            .withJsonBody("{\"refresh_token\":\"refresh.token.here\"}"));

        g.addTestCase(tc("TC003", "Logout", "Invalidate current user session",
                true, HttpMethod.POST, "/api/auth/logout", "lisa.garcia@company.com"));

        return g;
    }

    private static TestGroup buildOrderGroup() {
        TestGroup g = new TestGroup("Order APIs",
                "Order management and fulfillment API tests", "robert.taylor@company.com");

        g.addTestCase(tc("TC001", "Get Order Details", "Retrieve detailed order information",
                true, HttpMethod.GET, "/api/orders/ORD-12345", "robert.taylor@company.com")
            .withQueryParams(Arrays.asList(new Param("include", "items"), new Param("include", "customer")))
            .withResult(result(ExecutionStatus.PASSED, "", "200", "200",
                "{\"orderId\":\"ORD-12345\",\"status\":\"confirmed\"}",
                "{\"orderId\":\"ORD-12345\",\"status\":\"confirmed\"}",
                "2025-01-15 10:30:27")));

        g.addTestCase(tc("TC002", "Cancel Order", "Cancel an existing order",
                true, HttpMethod.PATCH, "/api/orders/ORD-12345/cancel", "robert.taylor@company.com")
            .withJsonBody("{\"reason\":\"customer_request\"}"));

        return g;
    }

    private static TestGroup buildPaymentGroup() {
        TestGroup g = new TestGroup("Payment APIs",
                "Payment processing and refund API tests (disabled by default)", "anna.martinez@company.com");

        g.addTestCase(tc("TC001", "Process Payment", "Process payment for an order",
                false, HttpMethod.POST, "/api/payments", "anna.martinez@company.com")
            .withJsonBody("{\"orderId\":\"ORD-12345\",\"amount\":99.99,\"currency\":\"USD\"}"));

        g.addTestCase(tc("TC002", "Get Payment Status", "Check payment status by payment ID",
                true, HttpMethod.GET, "/api/payments/PAY-99999", "anna.martinez@company.com"));

        return g;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static TestCase tc(String id, String name, String description,
                                boolean enabled, HttpMethod method,
                                String endpoint, String author) {
        return new TestCase(id, name, description, enabled, method, endpoint, author);
    }

    private static TestResult result(ExecutionStatus status, String differences,
                                     String srcStatus, String tgtStatus,
                                     String srcBody, String tgtBody, String executedAt) {
        TestResult r = new TestResult();
        r.setStatus(status);
        r.setDifferences(differences);
        r.setSourceStatus(srcStatus);
        r.setTargetStatus(tgtStatus);
        r.setSourceResponse(srcBody);
        r.setTargetResponse(tgtBody);
        r.setExecutedAt(executedAt);
        return r;
    }
}