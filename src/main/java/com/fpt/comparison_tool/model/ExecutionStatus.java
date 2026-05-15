package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ExecutionStatus {
    PENDING, PASSED, FAILED, ERROR;

    @JsonValue
    public String toValue() { return name().toLowerCase(); }

    @JsonCreator
    public static ExecutionStatus from(String v) {
        if (v == null || v.isBlank()) return PENDING;
        for (ExecutionStatus s : values()) {
            if (s.name().equalsIgnoreCase(v) || s.toValue().equalsIgnoreCase(v)) return s;
        }
        return PENDING;
    }
}
