package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

import java.util.ArrayList;
import java.util.List;

public class TestGroup {

    @JacksonXmlProperty(isAttribute = true)
    private String name;

    // Long text — child element, not attribute
    private String description;

    @JacksonXmlProperty(isAttribute = true)
    private String owner;

    @JacksonXmlProperty(isAttribute = true)
    private boolean enabled = true;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "testCase")
    @com.fasterxml.jackson.annotation.JsonProperty("testCases")
    private List<TestCase> testCases;

    public TestGroup() {
        this.testCases = new ArrayList<>();
    }

    public TestGroup(String name, String description, String owner) {
        this.name = name;
        this.description = description;
        this.owner = owner;
        this.testCases = new ArrayList<>();
    }

    @JsonIgnore
    public String getSheetName() {
        return "TC - " + name;
    }

    public void addTestCase(TestCase testCase) { this.testCases.add(testCase); }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public List<TestCase> getTestCases() { return testCases; }
    public void setTestCases(List<TestCase> testCases) { this.testCases = testCases; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}