package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * The unit of a test result. Preconditions run first (a failure blocks the case), then the steps in order, then
 * cleanup, which always runs. dependsOn lists keys of test cases of the same folder that must finish first.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "key", "name", "description", "tags", "disabled", "dependsOn", "stopOnFailure",
        "preconditions", "steps", "cleanup"})
public record TestCase(String id, String key, String name, String description, List<String> tags, Boolean disabled,
                       List<String> dependsOn, Boolean stopOnFailure, List<Step> preconditions, List<Step> steps,
                       List<Step> cleanup) implements Node {

    public TestCase {
        id = id == null ? "" : id;
        key = key == null ? "" : key.trim();
        name = name == null ? "" : name;
        description = Lists.text(description);
        tags = tags == null ? List.of() : tags.stream().filter(t -> t != null && !t.isBlank()).map(String::trim).toList();
        disabled = Lists.flag(disabled);
        dependsOn = dependsOn == null ? List.of()
                : dependsOn.stream().filter(t -> t != null && !t.isBlank()).map(String::trim).distinct().toList();
        stopOnFailure = Lists.flag(stopOnFailure);
        preconditions = Lists.of(preconditions);
        steps = Lists.of(steps);
        cleanup = Lists.of(cleanup);
    }

    @Override
    public boolean active() {
        return disabled == null;
    }

    public String label() {
        if (key.isEmpty()) return name;
        if (name.isEmpty() || name.equals(key)) return key;
        return key + " " + name;
    }
}
