package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * How the children of a folder run. cases: parallel (default) or serial; folders: serial (default) or parallel.
 * The steps inside one test case always run in order.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunPolicy(String cases, String folders) {

    public static final String PARALLEL = "parallel";
    public static final String SERIAL = "serial";

    public RunPolicy {
        cases = SERIAL.equalsIgnoreCase(cases) ? SERIAL : (PARALLEL.equalsIgnoreCase(cases) ? PARALLEL : null);
        folders = PARALLEL.equalsIgnoreCase(folders) ? PARALLEL : (SERIAL.equalsIgnoreCase(folders) ? SERIAL : null);
    }

    public boolean casesParallel(boolean inherited) {
        return cases == null ? inherited : PARALLEL.equals(cases);
    }

    public boolean foldersParallel(boolean inherited) {
        return folders == null ? inherited : PARALLEL.equals(folders);
    }
}
