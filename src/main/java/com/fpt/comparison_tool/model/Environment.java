package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** A target system: base URL, default headers, auth profile and variables ({{baseUrl}} is implicit). */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"name", "baseUrl", "auth", "headers", "variables"})
public record Environment(String name, String baseUrl, String auth, List<KeyValue> headers, List<KeyValue> variables) {

    public Environment {
        name = name == null ? "" : name;
        baseUrl = Lists.text(baseUrl);
        auth = Lists.text(auth);
        headers = Lists.of(headers);
        variables = Lists.of(variables);
    }
}
