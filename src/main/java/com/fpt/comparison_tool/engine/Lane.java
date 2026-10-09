package com.fpt.comparison_tool.engine;

import com.fpt.comparison_tool.model.Environment;

/** One side of a run: the environment under test (target) or, in a compare run, the reference (baseline). */
public record Lane(String name, Environment env) {

    public static final String TARGET = "target";
    public static final String BASELINE = "baseline";

    public boolean baseline() {
        return BASELINE.equals(name);
    }
}
