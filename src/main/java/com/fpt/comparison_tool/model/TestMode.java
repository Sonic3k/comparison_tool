package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum TestMode {
    COMPARISON("comparison"),
    AUTOMATION("automation"),
    BOTH("both");

    private final String value;
    TestMode(String v) { this.value = v; }

    @JsonValue
    public String getValue() { return value; }

    @JsonCreator
    public static TestMode from(String v) {
        if (v == null) return COMPARISON;
        for (TestMode m : values()) {
            if (m.value.equalsIgnoreCase(v) || m.name().equalsIgnoreCase(v)) return m;
        }
        return COMPARISON;
    }
}
