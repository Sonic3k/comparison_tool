package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AuthType {
    NONE, BASIC, BEARER, CLIENT_CREDENTIALS, SAML;

    @JsonValue
    public String toValue() { return name().toLowerCase(); }

    @JsonCreator
    public static AuthType from(String v) {
        if (v == null || v.isBlank()) return NONE;
        for (AuthType t : values()) {
            if (t.name().equalsIgnoreCase(v) || t.toValue().equalsIgnoreCase(v)) return t;
        }
        return NONE;
    }
}
