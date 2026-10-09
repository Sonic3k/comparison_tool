package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Advanced step settings. compare=false keeps the step out of the baseline/target diff of a compare run;
 * required=true on a setup step blocks the test cases of the folder when the step does not pass.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public record StepOptions(Integer delayMs, Integer timeoutMs, Retry retry, Boolean compare, List<String> ignoreFields,
                          Boolean required) {

    public StepOptions {
        delayMs = delayMs == null || delayMs <= 0 ? null : delayMs;
        timeoutMs = timeoutMs == null || timeoutMs <= 0 ? null : timeoutMs;
        if (retry != null && retry.times() == null) retry = null;
        compare = Boolean.FALSE.equals(compare) ? Boolean.FALSE : null;
        ignoreFields = Lists.of(ignoreFields);
        required = Lists.flag(required);
    }

    public static StepOptions none() {
        return new StepOptions(null, null, null, null, null, null);
    }

    public boolean compareOn() {
        return compare == null;
    }

    public boolean requiredOn() {
        return Boolean.TRUE.equals(required);
    }
}
