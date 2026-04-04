package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

import java.util.ArrayList;
import java.util.List;

public class TestCase {

    @JacksonXmlProperty(isAttribute = true)
    private String id;

    @JacksonXmlProperty(isAttribute = true)
    private String name;

    private String description;

    @JacksonXmlProperty(isAttribute = true)
    private boolean enabled;

    /** Whether to run comparison, automation, or both. Default = comparison. */
    @JacksonXmlProperty(isAttribute = true)
    private VerificationMode verificationMode = VerificationMode.COMPARISON;

    @JacksonXmlProperty(isAttribute = true)
    private HttpMethod method;

    @JacksonXmlProperty(isAttribute = true)
    private String endpoint;

    @JacksonXmlElementWrapper(localName = "queryParams")
    @JacksonXmlProperty(localName = "queryParam")
    private List<Param> queryParams;

    @JacksonXmlElementWrapper(localName = "formParams")
    @JacksonXmlProperty(localName = "formParam")
    private List<Param> formParams;

    private String jsonBody;
    private String headers;

    @JacksonXmlProperty(isAttribute = true)
    private String author;

    private ComparisonConfig comparisonConfig;
    private AutomationConfig automationConfig;
    private TestResult result;

    public TestCase() {
        this.enabled = true;
        this.verificationMode = VerificationMode.COMPARISON;
        this.queryParams = new ArrayList<>();
        this.formParams = new ArrayList<>();
        this.result = new TestResult();
    }

    public TestCase(String id, String name, String description, boolean enabled,
                    HttpMethod method, String endpoint, String author) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.enabled = enabled;
        this.verificationMode = VerificationMode.COMPARISON;
        this.method = method;
        this.endpoint = endpoint;
        this.author = author;
        this.queryParams = new ArrayList<>();
        this.formParams = new ArrayList<>();
        this.result = new TestResult();
    }

    // ── Fluent setters ─────────────────────────────────────────────────────────
    public TestCase withQueryParams(List<Param> p)               { this.queryParams = p;       return this; }
    public TestCase withFormParams(List<Param> p)                { this.formParams = p;        return this; }
    public TestCase withJsonBody(String b)                       { this.jsonBody = b;          return this; }
    public TestCase withHeaders(String h)                        { this.headers = h;           return this; }
    public TestCase withComparison(ComparisonConfig c)           { this.comparisonConfig = c;  return this; }
    public TestCase withAutomation(AutomationConfig a)           { this.automationConfig = a;  return this; }
    public TestCase withResult(TestResult r)                     { this.result = r;            return this; }

    // ── Excel helpers ──────────────────────────────────────────────────────────
    @JsonIgnore
    public String getQueryParamsAsString() { return paramsToString(queryParams); }

    @JsonIgnore
    public String getFormParamsAsString() { return paramsToString(formParams); }

    private String paramsToString(List<Param> params) {
        if (params == null || params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Param p : params) {
            if (sb.length() > 0) sb.append("&");
            sb.append(p.getKey()).append("=").append(p.getValue());
        }
        return sb.toString();
    }

    // ── Getters / Setters ──────────────────────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public VerificationMode getVerificationMode() { return verificationMode; }
    public void setVerificationMode(VerificationMode verificationMode) { this.verificationMode = verificationMode != null ? verificationMode : VerificationMode.COMPARISON; }

    public HttpMethod getMethod() { return method; }
    public void setMethod(HttpMethod method) { this.method = method; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public List<Param> getQueryParams() { return queryParams; }
    public void setQueryParams(List<Param> queryParams) { this.queryParams = queryParams; }

    public List<Param> getFormParams() { return formParams; }
    public void setFormParams(List<Param> formParams) { this.formParams = formParams; }

    public String getJsonBody() { return jsonBody; }
    public void setJsonBody(String jsonBody) { this.jsonBody = jsonBody; }

    public String getHeaders() { return headers; }
    public void setHeaders(String headers) { this.headers = headers; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public ComparisonConfig getComparisonConfig() { return comparisonConfig; }
    public void setComparisonConfig(ComparisonConfig c) { this.comparisonConfig = c; }

    public AutomationConfig getAutomationConfig() { return automationConfig; }
    public void setAutomationConfig(AutomationConfig a) { this.automationConfig = a; }

    public TestResult getResult() { return result; }
    public void setResult(TestResult result) { this.result = result; }
}
