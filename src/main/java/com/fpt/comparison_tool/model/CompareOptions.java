package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** How baseline and target responses are diffed in compare runs. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CompareOptions(List<String> ignoreFields, Boolean ignoreArrayOrder, Boolean caseSensitive,
                             Double numericTolerance, Boolean compareErrorResponses) {

    public CompareOptions {
        ignoreFields = Lists.of(ignoreFields);
        ignoreArrayOrder = Lists.flag(ignoreArrayOrder);
        compareErrorResponses = Lists.flag(compareErrorResponses);
        caseSensitive = Boolean.FALSE.equals(caseSensitive) ? Boolean.FALSE : null;
        numericTolerance = numericTolerance == null || numericTolerance <= 0 ? null : numericTolerance;
    }

    public static CompareOptions defaults() {
        return new CompareOptions(null, null, null, null, null);
    }

    public boolean caseSensitiveOn() {
        return caseSensitive == null;
    }

    /** Suite options with step-level ignore fields appended. */
    public CompareOptions withExtraIgnores(List<String> extra) {
        if (extra == null || extra.isEmpty()) return this;
        List<String> all = new ArrayList<>(ignoreFields);
        all.addAll(extra);
        return new CompareOptions(all, ignoreArrayOrder, caseSensitive, numericTolerance, compareErrorResponses);
    }
}
