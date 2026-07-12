package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class TestGroup {

    @JacksonXmlProperty(isAttribute = true)
    private String name;

    // Long text — child element, not attribute
    private String description;

    @JacksonXmlProperty(isAttribute = true)
    private String owner;

    @JacksonXmlProperty(isAttribute = true)
    private boolean enabled = true;

    /**
     * Registry of logical Test Cases in this group. Each entry can be
     * referenced by one or more TestRequests via {@code testCaseId}.
     * Maintained automatically by {@link #normalize()} — every distinct
     * testCaseId used by a request always has a matching def.
     */
    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "testCaseDef")
    @JsonProperty("testCaseDefs")
    private List<TestCaseDef> testCaseDefs;

    /**
     * The executable HTTP requests of this group (flat, ordered).
     * Legacy formats used the key "testCases" (JSON) / element "testCase" (XML)
     * — both are accepted on import via @JsonAlias.
     */
    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "testRequest")
    @JsonProperty("testRequests")
    @JsonAlias({"testCases", "testCase"})
    private List<TestRequest> testRequests;

    public TestGroup() {
        this.testRequests = new ArrayList<>();
        this.testCaseDefs = new ArrayList<>();
    }

    public TestGroup(String name, String description, String owner) {
        this.name = name;
        this.description = description;
        this.owner = owner;
        this.testRequests = new ArrayList<>();
        this.testCaseDefs = new ArrayList<>();
    }

    @JsonIgnore
    public String getSheetName() {
        return "TC - " + name;
    }

    public void addTestRequest(TestRequest testRequest) { this.testRequests.add(testRequest); }

    // ── Test Case invariants ───────────────────────────────────────────────────

    /**
     * Enforces the Test Case invariants:
     *  1. Every request has a testCaseId (default = its own id → 1 request : 1 test case).
     *  2. Every distinct testCaseId has a matching TestCaseDef
     *     (auto-created; name defaults to the request name when the test case
     *     has exactly one member, otherwise to the testCaseId itself).
     *
     * Called after every import and after request create/update so old-format
     * files (without testCaseId / testCaseDefs) load seamlessly.
     */
    public void normalize() {
        if (testRequests == null) testRequests = new ArrayList<>();
        if (testCaseDefs == null) testCaseDefs = new ArrayList<>();

        for (TestRequest r : testRequests) {
            if (r.getTestCaseId() == null || r.getTestCaseId().isBlank()) {
                r.setTestCaseId(r.getId());
            }
        }

        Map<String, List<TestRequest>> members = new LinkedHashMap<>();
        for (TestRequest r : testRequests) {
            members.computeIfAbsent(r.getTestCaseId(), k -> new ArrayList<>()).add(r);
        }

        Set<String> existing = testCaseDefs.stream()
                .map(TestCaseDef::getId)
                .collect(Collectors.toSet());

        for (Map.Entry<String, List<TestRequest>> e : members.entrySet()) {
            if (e.getKey() == null || existing.contains(e.getKey())) continue;
            String defName = e.getKey();
            if (e.getValue().size() == 1) {
                String reqName = e.getValue().get(0).getName();
                if (reqName != null && !reqName.isBlank()) defName = reqName;
            }
            testCaseDefs.add(new TestCaseDef(e.getKey(), defName, null));
        }
    }

    @JsonIgnore
    public TestRequest findTestRequest(String id) {
        if (testRequests == null || id == null) return null;
        return testRequests.stream().filter(r -> id.equals(r.getId())).findFirst().orElse(null);
    }

    @JsonIgnore
    public TestCaseDef findTestCaseDef(String id) {
        if (testCaseDefs == null || id == null) return null;
        return testCaseDefs.stream().filter(d -> id.equals(d.getId())).findFirst().orElse(null);
    }

    // ── Getters / Setters ──────────────────────────────────────────────────────

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<TestCaseDef> getTestCaseDefs() { return testCaseDefs; }
    public void setTestCaseDefs(List<TestCaseDef> defs) { this.testCaseDefs = defs; }

    public List<TestRequest> getTestRequests() { return testRequests; }
    public void setTestRequests(List<TestRequest> testRequests) { this.testRequests = testRequests; }
}
