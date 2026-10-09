package com.fpt.comparison_tool.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A layer of variables. Lookups walk from this layer to the outermost one; writes stay in this layer, so test cases
 * running in parallel never see each other's values.
 */
public final class Scope {

    private final Scope parent;
    private final String label;
    private final Map<String, String> vars = new ConcurrentHashMap<>();

    public Scope(Scope parent, String label) {
        this.parent = parent;
        this.label = label;
    }

    public Scope child(String childLabel) {
        return new Scope(this, childLabel);
    }

    public String get(String name) {
        for (Scope s = this; s != null; s = s.parent) {
            String v = s.vars.get(name);
            if (v != null) return v;
        }
        return null;
    }

    public void set(String name, String value) {
        if (name == null || name.isBlank() || value == null) return;
        vars.put(name, value);
    }

    public void setAll(Map<String, String> values) {
        if (values != null) values.forEach(this::set);
    }

    public Map<String, String> local() {
        return new LinkedHashMap<>(new java.util.TreeMap<>(vars));
    }

    public Scope parent() {
        return parent;
    }

    public String label() {
        return label;
    }
}
