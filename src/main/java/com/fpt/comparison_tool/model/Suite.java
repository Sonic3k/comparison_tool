package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * A test suite file: environments, auth profiles, suite variables and defaults, a setup and teardown that wrap the
 * whole run, and a tree of folders and test cases.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"schema", "name", "description", "version", "settings", "environments", "authProfiles",
        "variables", "defaults", "setup", "teardown", "items"})
public record Suite(String schema, String name, String description, String version, Settings settings,
                    List<Environment> environments, List<AuthProfile> authProfiles, List<KeyValue> variables,
                    Defaults defaults, List<Step> setup, List<Step> teardown, List<Node> items) {

    public static final String SCHEMA = "comparison-tool/suite@2";

    public Suite {
        schema = SCHEMA;
        name = name == null ? "" : name;
        description = Lists.text(description);
        version = Lists.text(version);
        if (settings != null && settings.equals(Settings.defaults())) settings = null;
        environments = Lists.of(environments);
        authProfiles = Lists.of(authProfiles);
        variables = Lists.of(variables);
        if (defaults != null && defaults.equals(Defaults.empty())) defaults = null;
        setup = Lists.of(setup);
        teardown = Lists.of(teardown);
        items = Lists.of(items);
    }

    public Settings settingsOrDefault() {
        return settings == null ? Settings.defaults() : settings;
    }

    public Defaults defaultsOrEmpty() {
        return defaults == null ? Defaults.empty() : defaults;
    }

    public Environment environment(String envName) {
        if (envName == null) return null;
        return environments.stream().filter(e -> e.name().equals(envName)).findFirst().orElse(null);
    }

    public AuthProfile authProfile(String profileName) {
        if (profileName == null) return null;
        return authProfiles.stream().filter(p -> p.name().equals(profileName)).findFirst().orElse(null);
    }
}
