package com.fpt.comparison_tool.result;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * A test case inside the run's scope that did not run because it, or a folder above it, is disabled. position is
 * the index in the header's caseIds before which it sits in suite order. folder names the disabled folder, if that
 * is the reason.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"caseId", "key", "name", "folderId", "path", "position", "folder"})
public record DisabledCase(String caseId, String key, String name, String folderId, List<String> path, int position,
                           String folder) {
}
