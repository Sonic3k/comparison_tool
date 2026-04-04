package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Phase {
    SETUP("setup"),
    TEST("test"),
    TEARDOWN("teardown");

    private final String value;
    Phase(String v) { this.value = v; }

    @JsonValue
    public String getValue() { return value; }

    @JsonCreator
    public static Phase from(String v) {
        if (v == null || v.isBlank()) return TEST;
        for (Phase p : values()) {
            if (p.value.equalsIgnoreCase(v) || p.name().equalsIgnoreCase(v)) return p;
        }
        return TEST;
    }
}
