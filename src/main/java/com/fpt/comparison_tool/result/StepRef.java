package com.fpt.comparison_tool.result;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Points at a step by phase and 0-based index, for example a step that is disabled and did not run. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StepRef(String phase, int index, String name) {
}
