package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Repeat a step until it passes: up to {@code times} extra attempts, {@code intervalMs} apart. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Retry(Integer times, Integer intervalMs) {

    public Retry {
        times = times == null || times <= 0 ? null : times;
        intervalMs = intervalMs == null || intervalMs <= 0 ? null : intervalMs;
    }

    public int timesOrZero() {
        return times == null ? 0 : times;
    }

    public int intervalOrDefault() {
        return intervalMs == null ? 1000 : intervalMs;
    }
}
