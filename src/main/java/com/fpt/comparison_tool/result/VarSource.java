package com.fpt.comparison_tool.result;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Where a variable's value came from. kind is one of env (owner = environment name), suite (suite variables),
 * override (values given for the run), builtin ({{$guid}} and friends), reused (setup values of an earlier run, owner
 * = folder), setup / teardown (a folder or suite hook step) or pre / step / cleanup (a step of a test case, owner =
 * its key), or unset when nothing had the name. step is 1-based within its phase; name is the step name.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record VarSource(String kind, String owner, Integer step, String name) {

    public static final VarSource SUITE = new VarSource("suite", null, null, null);
    public static final VarSource OVERRIDE = new VarSource("override", null, null, null);
    public static final VarSource BUILTIN = new VarSource("builtin", null, null, null);
    public static final VarSource UNSET = new VarSource("unset", null, null, null);

    public static VarSource env(String envName) {
        return new VarSource("env", envName, null, null);
    }

    public static VarSource reused(String owner) {
        return new VarSource("reused", owner, null, null);
    }

    /** A value saved by a step: phase pre, step, cleanup, setup or teardown; index is 0-based. */
    public static VarSource step(String phase, String owner, int index, String stepName) {
        return new VarSource(phase, owner, index + 1, stepName);
    }
}
