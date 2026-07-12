package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/**
 * A logical Test Case — maps 1-1 to a manual regression test case.
 *
 * A TestCaseDef does not execute anything by itself: it is a lightweight
 * registry entry inside a {@link TestGroup}. One or more {@link TestRequest}s
 * reference it via {@code TestRequest.testCaseId}. The rolled-up status of a
 * Test Case is derived from its member requests (all passed → passed,
 * any failed/error → failed/error, otherwise pending).
 */
public class TestCaseDef {

    @JacksonXmlProperty(isAttribute = true)
    private String id;

    @JacksonXmlProperty(isAttribute = true)
    private String name;

    private String description;

    public TestCaseDef() {}

    public TestCaseDef(String id, String name, String description) {
        this.id = id;
        this.name = name;
        this.description = description;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
