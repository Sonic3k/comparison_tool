package com.fpt.comparison_tool.result;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * Result of a suite or folder setup / teardown. folderId is empty for the suite. vars holds, per lane (target,
 * baseline), the variables the hook left in its scope so a later run can reuse them instead of seeding again.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"folderId", "path", "hook", "status", "reason", "startedAt", "durationMs", "steps", "vars"})
public record HookResult(String folderId, List<String> path, String hook, String status, String reason,
                         String startedAt, Long durationMs, List<StepResult> steps,
                         Map<String, Map<String, String>> vars) {

    public Status statusEnum() {
        return Status.of(status);
    }

    public HookResult withSteps(List<StepResult> newSteps) {
        return new HookResult(folderId, path, hook, status, reason, startedAt, durationMs, newSteps, vars);
    }
}
