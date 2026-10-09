package com.fpt.comparison_tool.result;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * Result of one step. phase: pre, step, cleanup (test case) or setup, teardown (suite and folder hooks).
 * target is the exchange with the environment under test; baseline is filled in compare runs.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"phase", "index", "name", "status", "reason", "attempts", "durationMs", "assertions", "diffs",
        "extracted", "missed", "target", "baseline"})
public record StepResult(String phase, int index, String name, String status, String reason, Integer attempts,
                         Long durationMs, List<AssertionResult> assertions, List<String> diffs,
                         Map<String, String> extracted, List<String> missed, Exchange target, Exchange baseline) {

    public Status statusEnum() {
        return Status.of(status);
    }

    public StepResult withExchanges(Exchange newTarget, Exchange newBaseline) {
        return new StepResult(phase, index, name, status, reason, attempts, durationMs, assertions, diffs, extracted,
                missed, newTarget, newBaseline);
    }

    public static StepResult skipped(String phase, int index, String name, String reason) {
        return new StepResult(phase, index, name, Status.SKIPPED.value(), reason, null, null, null, null, null, null, null,
                null);
    }
}
