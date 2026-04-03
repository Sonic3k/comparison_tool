package com.fpt.comparison_tool.model;

public class AutomationConfig {

    /** Expected HTTP status code, e.g. "200" or "2xx" */
    private String expectedStatus;

    /**
     * Multiline DSL assertions against target response body.
     * Each line is one assertion, e.g.:
     *   $.id == 1
     *   $.status == "active"
     *   $.error exists
     *   $.items.length > 0
     *   $.message contains "not found"
     */
    private String expectedBody;

    /** Expected response headers, one per line: "Header-Name: value" */
    private String expectedHeaders;

    /** Max allowed response time in milliseconds (0 = no limit) */
    private int maxResponseTime;

    public AutomationConfig() {}

    public String getExpectedStatus() { return expectedStatus; }
    public void setExpectedStatus(String expectedStatus) { this.expectedStatus = expectedStatus; }

    public String getExpectedBody() { return expectedBody; }
    public void setExpectedBody(String expectedBody) { this.expectedBody = expectedBody; }

    public String getExpectedHeaders() { return expectedHeaders; }
    public void setExpectedHeaders(String expectedHeaders) { this.expectedHeaders = expectedHeaders; }

    public int getMaxResponseTime() { return maxResponseTime; }
    public void setMaxResponseTime(int maxResponseTime) { this.maxResponseTime = maxResponseTime; }
}
