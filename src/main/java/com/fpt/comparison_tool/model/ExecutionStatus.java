package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ExecutionStatus {
    PENDING, PASSED, FAILED, ERROR;

    @JsonValue
    public String toValue() { return name().toLowerCase(); }
}