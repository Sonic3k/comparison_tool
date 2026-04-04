package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum VerificationMode {
    COMPARISON("comparison"),
    AUTOMATION("automation"),
    BOTH("both"),
    NONE("none");

    private final String value;
    VerificationMode(String v) { this.value = v; }

    @JsonValue
    public String getValue() { return value; }

    @JsonCreator
    public static VerificationMode from(String v) {
        if (v == null) return COMPARISON;
        for (VerificationMode m : values()) {
            if (m.value.equalsIgnoreCase(v) || m.name().equalsIgnoreCase(v)) return m;
        }
        return COMPARISON;
    }
}
