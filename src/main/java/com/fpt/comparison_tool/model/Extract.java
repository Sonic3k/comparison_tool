package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Saves a value of the response as a variable. path: $.json.path, $status, $header.Name or $ (whole body).
 * scope: empty = the test case (or the folder for setup steps); "folder" = shared with the folder, for later
 * test cases that declare dependsOn and for the folder teardown; "suite" = shared with the whole run, for the
 * suite teardown.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Extract(String name, String path, String scope) {

    public static final String FOLDER = "folder";
    public static final String SUITE = "suite";

    public Extract {
        name = name == null ? "" : name.trim();
        path = path == null ? "" : path.trim();
        scope = FOLDER.equalsIgnoreCase(scope) ? FOLDER : (SUITE.equalsIgnoreCase(scope) ? SUITE : null);
    }

    public boolean toFolder() {
        return FOLDER.equals(scope);
    }

    public boolean toSuite() {
        return SUITE.equals(scope);
    }

    public Extract withScope(String newScope) {
        return new Extract(name, path, newScope);
    }
}
