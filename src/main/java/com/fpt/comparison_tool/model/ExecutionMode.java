package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ExecutionMode {
    PARALLEL, SOURCE_FIRST;

    @JsonValue
    public String toValue() { return name().toLowerCase(); }

    @JsonCreator
    public static ExecutionMode from(String v) {
        if (v == null || v.isBlank()) return PARALLEL;
        for (ExecutionMode m : values()) {
            if (m.name().equalsIgnoreCase(v) || m.toValue().equalsIgnoreCase(v)) return m;
        }
        return PARALLEL;
    }
}
