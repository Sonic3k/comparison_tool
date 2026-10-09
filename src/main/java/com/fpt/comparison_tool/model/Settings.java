package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Run settings of a suite: how many test cases may run at once, the environments preselected for a run,
 * how top-level items run and how compare runs diff responses.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Settings(Integer concurrency, String env, String baselineEnv, RunPolicy run, CompareOptions compare) {

    public static final int DEFAULT_CONCURRENCY = 4;

    public Settings {
        concurrency = concurrency == null || concurrency <= 0 ? null : concurrency;
        env = Lists.text(env);
        baselineEnv = Lists.text(baselineEnv);
        if (run != null && run.cases() == null && run.folders() == null) run = null;
    }

    public static Settings defaults() {
        return new Settings(null, null, null, null, null);
    }

    public int concurrencyOrDefault() {
        return concurrency == null ? DEFAULT_CONCURRENCY : concurrency;
    }

    public RunPolicy runOrDefault() {
        return run == null ? new RunPolicy(null, null) : run;
    }

    public CompareOptions compareOrDefault() {
        return compare == null ? CompareOptions.defaults() : compare;
    }
}
