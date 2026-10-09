package com.fpt.comparison_tool.result;

import java.util.Locale;

/** Outcome of a step, hook or test case. */
public enum Status {
    PASS, FAIL, ERROR, BLOCKED, SKIPPED;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Status of(String v) {
        if (v == null) return SKIPPED;
        try {
            return valueOf(v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SKIPPED;
        }
    }

    /** The worse of two outcomes: error > fail > blocked > skipped > pass. */
    public static Status worst(Status a, Status b) {
        return rank(a) >= rank(b) ? a : b;
    }

    private static int rank(Status s) {
        return switch (s) {
            case PASS -> 0;
            case SKIPPED -> 1;
            case BLOCKED -> 2;
            case FAIL -> 3;
            case ERROR -> 4;
        };
    }
}
