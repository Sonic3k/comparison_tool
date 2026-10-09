package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What a step must return. status: 200, 2xx or a list such as 200,404. assertions: one DSL line each.
 * A step without status and without assertions passes when the HTTP status is below 400.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Expect(String status, List<String> assertions, Integer maxTimeMs) {

    public Expect {
        status = Lists.text(status);
        assertions = assertions == null ? List.of()
                : assertions.stream().filter(a -> a != null && !a.isBlank()).map(String::trim).toList();
        maxTimeMs = maxTimeMs == null || maxTimeMs <= 0 ? null : maxTimeMs;
    }

    public boolean blank() {
        return status == null && assertions.isEmpty() && maxTimeMs == null;
    }
}
