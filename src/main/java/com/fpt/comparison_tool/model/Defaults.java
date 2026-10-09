package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Values inherited by every step below the suite or folder that declares them. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Defaults(List<KeyValue> headers, String auth, Integer timeoutMs) {

    public Defaults {
        headers = Lists.of(headers);
        auth = Lists.text(auth);
        timeoutMs = timeoutMs == null || timeoutMs <= 0 ? null : timeoutMs;
    }

    public static Defaults empty() {
        return new Defaults(null, null, null);
    }
}
