package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** A name/value pair (header, query parameter, form field, variable); disabled rows are kept but not sent. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public record KeyValue(String key, String value, Boolean disabled) {

    public KeyValue {
        key = key == null ? "" : key;
        value = value == null ? "" : value;
        disabled = Boolean.TRUE.equals(disabled) ? Boolean.TRUE : null;
    }

    public static KeyValue of(String key, String value) {
        return new KeyValue(key, value, null);
    }

    public boolean active() {
        return disabled == null && !key.isBlank();
    }
}
