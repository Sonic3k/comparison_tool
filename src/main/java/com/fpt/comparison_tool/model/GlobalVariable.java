package com.fpt.comparison_tool.model;

/**
 * A session-global variable, Postman-style: written automatically whenever a
 * request's extractVariables succeeds (last write wins), editable in the UI,
 * and persisted through JSON/XML/Excel export so single-request re-runs can
 * resolve {{placeholders}} without re-running the whole flow.
 */
public class GlobalVariable {

    private String name;
    private String value;
    private String updatedAt;

    public GlobalVariable() { }

    public GlobalVariable(String name, String value, String updatedAt) {
        this.name = name; this.value = value; this.updatedAt = updatedAt;
    }

    public String getName()               { return name; }
    public void setName(String name)      { this.name = name; }
    public String getValue()              { return value; }
    public void setValue(String value)    { this.value = value; }
    public String getUpdatedAt()          { return updatedAt; }
    public void setUpdatedAt(String u)    { this.updatedAt = u; }
}
