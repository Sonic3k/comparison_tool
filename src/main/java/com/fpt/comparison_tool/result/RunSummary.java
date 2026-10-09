package com.fpt.comparison_tool.result;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** Last line of a run file. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunSummary(int total, Map<String, Integer> counts, String finishedAt, Long durationMs, Boolean stopped,
                         String error) {

    public int count(Status s) {
        return counts == null ? 0 : counts.getOrDefault(s.value(), 0);
    }
}
