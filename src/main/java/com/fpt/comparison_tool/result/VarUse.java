package com.fpt.comparison_tool.result;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * A variable one step used: the value it had when the step was sent (null when it had none), where that value came
 * from and the places that used it: URL, Query name, Header name, Body, Auth or Check n (n counts the step's checks
 * from 1, in the order they are reported).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"name", "value", "from", "usedIn"})
public record VarUse(String name, String value, VarSource from, List<String> usedIn) {

    public VarUse truncated(int limitChars) {
        if (value == null || value.length() <= limitChars) return this;
        return new VarUse(name, value.substring(0, limitChars) + "…", from, usedIn);
    }
}
