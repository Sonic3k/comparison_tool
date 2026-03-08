package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ExecutionMode {
    PARALLEL, SOURCE_FIRST;

    @JsonValue
    public String toValue() { return name().toLowerCase(); }
}