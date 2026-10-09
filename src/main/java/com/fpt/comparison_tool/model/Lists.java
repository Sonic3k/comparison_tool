package com.fpt.comparison_tool.model;

import java.util.List;

/** Null-safe list helpers shared by the model records. */
public final class Lists {

    private Lists() {
    }

    public static <T> List<T> of(List<T> list) {
        return list == null ? List.of() : List.copyOf(list.stream().filter(java.util.Objects::nonNull).toList());
    }

    public static String text(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public static Boolean flag(Boolean b) {
        return Boolean.TRUE.equals(b) ? Boolean.TRUE : null;
    }
}
