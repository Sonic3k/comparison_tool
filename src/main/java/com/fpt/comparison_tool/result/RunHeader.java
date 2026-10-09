package com.fpt.comparison_tool.result;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** First line of a run file: what ran, where, with which options. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"schema", "runId", "suite", "suiteFile", "env", "baselineEnv", "startedAt", "selection",
        "concurrency", "capture", "hooks", "planned", "tool", "caseIds"})
public record RunHeader(String schema, String runId, String suite, String suiteFile, String env, String baselineEnv,
                        String startedAt, String selection, Integer concurrency, String capture, String hooks,
                        Integer planned, String tool, List<String> caseIds) {

    public static final String SCHEMA = "comparison-tool/run@2";
}
