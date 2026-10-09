package com.fpt.comparison_tool.result;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * First line of a run file: what ran, where, with which options. disabled lists the test cases of the run's scope
 * that were skipped because they are disabled; variables holds, per lane, the environment and suite values the run
 * started with; parent names the run whose test cases this run repeats in place.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"schema", "runId", "suite", "suiteFile", "env", "baselineEnv", "startedAt", "selection",
        "concurrency", "capture", "hooks", "planned", "tool", "parent", "caseIds", "disabled", "variables"})
public record RunHeader(String schema, String runId, String suite, String suiteFile, String env, String baselineEnv,
                        String startedAt, String selection, Integer concurrency, String capture, String hooks,
                        Integer planned, String tool, List<String> caseIds, List<DisabledCase> disabled,
                        Map<String, Map<String, String>> variables, String parent) {

    public static final String SCHEMA = "comparison-tool/run@2";

    public RunHeader(String schema, String runId, String suite, String suiteFile, String env, String baselineEnv,
                     String startedAt, String selection, Integer concurrency, String capture, String hooks,
                     Integer planned, String tool, List<String> caseIds) {
        this(schema, runId, suite, suiteFile, env, baselineEnv, startedAt, selection, concurrency, capture, hooks, planned,
                tool, caseIds, null, null, null);
    }
}
