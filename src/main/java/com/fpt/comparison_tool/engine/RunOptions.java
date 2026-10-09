package com.fpt.comparison_tool.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Per-run choices. capture: all (keep every body), failed (bodies of test cases that did not pass, the default) or
 * none. hooks: run (default), reuse (skip setup and teardown and seed their variables from {@code reuse}, keyed
 * "suite" or folder id, then lane) or skip. variables override every other variable source. parent names the run
 * whose test cases this run repeats in place (the results are merged into that run by the caller).
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunOptions(Integer concurrency, String capture, Integer bodyLimitKb, String hooks,
                         Map<String, Map<String, Map<String, String>>> reuse, Map<String, String> variables,
                         String parent) {

    public static final String CAPTURE_ALL = "all";
    public static final String CAPTURE_FAILED = "failed";
    public static final String CAPTURE_NONE = "none";
    public static final String HOOKS_RUN = "run";
    public static final String HOOKS_REUSE = "reuse";
    public static final String HOOKS_SKIP = "skip";

    public RunOptions {
        capture = capture == null ? CAPTURE_FAILED : capture.trim().toLowerCase(java.util.Locale.ROOT);
        if (!capture.equals(CAPTURE_ALL) && !capture.equals(CAPTURE_NONE)) capture = CAPTURE_FAILED;
        hooks = hooks == null ? HOOKS_RUN : hooks.trim().toLowerCase(java.util.Locale.ROOT);
        if (!hooks.equals(HOOKS_REUSE) && !hooks.equals(HOOKS_SKIP)) hooks = HOOKS_RUN;
        reuse = reuse == null ? Map.of() : reuse;
        variables = variables == null ? Map.of() : variables;
        bodyLimitKb = bodyLimitKb == null || bodyLimitKb <= 0 ? 256 : bodyLimitKb;
        concurrency = concurrency == null || concurrency <= 0 ? null : concurrency;
    }

    public RunOptions(Integer concurrency, String capture, Integer bodyLimitKb, String hooks,
                      Map<String, Map<String, Map<String, String>>> reuse, Map<String, String> variables) {
        this(concurrency, capture, bodyLimitKb, hooks, reuse, variables, null);
    }

    public static RunOptions defaults() {
        return new RunOptions(null, null, null, null, null, null, null);
    }
}
