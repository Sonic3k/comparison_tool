package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * A group of test cases and sub-folders. setup runs once before anything inside the folder and its variables are
 * visible to everything inside; teardown runs once after everything inside, also after failures or a stop.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "name", "description", "disabled", "defaults", "run", "setup", "teardown", "items"})
public record Folder(String id, String name, String description, Boolean disabled, Defaults defaults, RunPolicy run,
                     List<Step> setup, List<Step> teardown, List<Node> items) implements Node {

    public Folder {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        description = Lists.text(description);
        disabled = Lists.flag(disabled);
        if (defaults != null && defaults.equals(Defaults.empty())) defaults = null;
        if (run != null && run.cases() == null && run.folders() == null) run = null;
        setup = Lists.of(setup);
        teardown = Lists.of(teardown);
        items = Lists.of(items);
    }

    @Override
    public boolean active() {
        return disabled == null;
    }

    public Defaults defaultsOrEmpty() {
        return defaults == null ? Defaults.empty() : defaults;
    }

    public RunPolicy runOrDefault() {
        return run == null ? new RunPolicy(null, null) : run;
    }
}
