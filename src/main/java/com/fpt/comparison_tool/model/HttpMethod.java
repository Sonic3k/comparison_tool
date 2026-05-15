package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum HttpMethod {
    GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS;

    @JsonValue
    public String toValue() { return name().toUpperCase(); }

    @JsonCreator
    public static HttpMethod from(String v) {
        if (v == null || v.isBlank()) return GET;
        for (HttpMethod m : values()) {
            if (m.name().equalsIgnoreCase(v)) return m;
        }
        return GET;
    }
}
