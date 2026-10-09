package com.fpt.comparison_tool.engine;

import com.fpt.comparison_tool.model.Suite;

/** Everything needed to start a run. baselineEnv makes it a compare run. */
public record RunRequest(Suite suite, String env, String baselineEnv, Selection selection, RunOptions options,
                         String suiteFile) {

    public RunRequest {
        selection = selection == null ? Selection.all() : selection;
        options = options == null ? RunOptions.defaults() : options;
        if (baselineEnv != null && baselineEnv.isBlank()) baselineEnv = null;
    }
}
