package com.fpt.comparison_tool.engine;

import com.fpt.comparison_tool.result.VarSource;

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
    private final Map<String, VarSource> origins = new ConcurrentHashMap<>();

    /** A value found by {@link #find}: where it was set (may be null) and the label of the layer that holds it. */
    public record Found(String value, VarSource from, String layer) {
    }

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

    public Found find(String name) {
        for (Scope s = this; s != null; s = s.parent) {
            String v = s.vars.get(name);
            if (v != null) return new Found(v, s.origins.get(name), s.label);
        }
        return null;
    }

    public void set(String name, String value) {
        set(name, value, null);
    }

    public void set(String name, String value, VarSource from) {
        if (name == null || name.isBlank() || value == null) return;
        vars.put(name, value);
        if (from != null) origins.put(name, from);
        else origins.remove(name);
    }

    public void setAll(Map<String, String> values) {
        if (values != null) values.forEach(this::set);
    }

    public void setAll(Map<String, String> values, VarSource from) {
        if (values != null) values.forEach((k, v) -> set(k, v, from));
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
