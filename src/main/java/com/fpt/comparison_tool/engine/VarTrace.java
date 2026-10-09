package com.fpt.comparison_tool.engine;

import com.fpt.comparison_tool.result.VarSource;
import com.fpt.comparison_tool.result.VarUse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Resolves the variables of one step on one lane and remembers what it used: each name, the value it had, where
 * that value came from and which parts of the step used it. Values of names that look like passwords or secrets
 * are masked in what is recorded (never in what is sent).
 */
final class VarTrace {

    private static final Pattern SECRET_NAME = Pattern.compile("(?i).*(password|passwd|pwd|secret).*");
    static final String MASK = "••••••";

    private final Scope scope;
    private final Map<String, String> overrides;
    private final Map<String, Entry> used = new LinkedHashMap<>();

    private static final class Entry {
        final String value;
        final VarSource from;
        final Set<String> where = new LinkedHashSet<>();

        Entry(String value, VarSource from) {
            this.value = value;
            this.from = from;
        }
    }

    VarTrace(Scope scope, Map<String, String> overrides) {
        this.scope = scope;
        this.overrides = overrides == null ? Map.of() : overrides;
    }

    /** A lookup that records every name it is asked for as used in {@code where}. */
    Function<String, String> lookup(String where) {
        return name -> {
            String value = name.startsWith("$") ? Templates.builtIn(name) : null;
            VarSource from;
            if (value != null) {
                from = VarSource.BUILTIN;
            } else if ((value = overrides.get(name)) != null) {
                from = VarSource.OVERRIDE;
            } else {
                Scope.Found f = scope == null ? null : scope.find(name);
                if (f != null) {
                    value = f.value();
                    from = f.from() != null ? f.from() : new VarSource("scope", f.layer(), null, null);
                } else {
                    from = VarSource.UNSET;
                }
            }
            note(name, value, from, where);
            return value;
        };
    }

    String resolve(String text, String where, Set<String> missing) {
        return Templates.substitute(text, lookup(where), missing);
    }

    /** Records a use that did not go through a template, such as the environment's base URL. */
    void note(String name, String value, VarSource from, String where) {
        Entry e = used.computeIfAbsent(name, n -> new Entry(value, from));
        if (where != null) e.where.add(where);
    }

    List<VarUse> uses() {
        List<VarUse> out = new ArrayList<>(used.size());
        used.forEach((name, e) -> out.add(new VarUse(name, e.value == null ? null : masked(name, e.value), e.from,
                List.copyOf(e.where))));
        return out;
    }

    /** The value to record for a variable: masked when its name says it is a password or a secret. */
    static String masked(String name, String value) {
        return value != null && !value.isEmpty() && SECRET_NAME.matcher(name).matches() ? MASK : value;
    }
}
