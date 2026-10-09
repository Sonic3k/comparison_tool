package com.fpt.comparison_tool.result;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** Result of one test case: its status, the first reason it did not pass and every step. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"caseId", "key", "name", "folderId", "path", "tags", "status", "reason", "cleanupFailed",
        "startedAt", "durationMs", "attempt", "steps", "disabledSteps", "triage"})
public record CaseResult(String caseId, String key, String name, String folderId, List<String> path, List<String> tags,
                         String status, String reason, Boolean cleanupFailed, String startedAt, Long durationMs,
                         List<StepResult> steps, Triage triage, List<StepRef> disabledSteps, Integer attempt) {

    public CaseResult(String caseId, String key, String name, String folderId, List<String> path, List<String> tags,
                      String status, String reason, Boolean cleanupFailed, String startedAt, Long durationMs,
                      List<StepResult> steps, Triage triage) {
        this(caseId, key, name, folderId, path, tags, status, reason, cleanupFailed, startedAt, durationMs, steps, triage,
                null, null);
    }

    public Status statusEnum() {
        return Status.of(status);
    }

    public CaseResult withSteps(List<StepResult> newSteps) {
        return new CaseResult(caseId, key, name, folderId, path, tags, status, reason, cleanupFailed, startedAt,
                durationMs, newSteps, triage, disabledSteps, attempt);
    }

    public CaseResult withTriage(Triage t) {
        return new CaseResult(caseId, key, name, folderId, path, tags, status, reason, cleanupFailed, startedAt,
                durationMs, steps, t, disabledSteps, attempt);
    }

    /** Review note added in the UI: label is one of defect, test, requirement, environment, known. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Triage(String label, String note) {
    }
}
