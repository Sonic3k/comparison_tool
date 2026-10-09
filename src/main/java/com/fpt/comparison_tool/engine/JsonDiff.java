package com.fpt.comparison_tool.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.comparison_tool.model.CompareOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Diffs a baseline and a target response: HTTP status, then the JSON body field by field (ignore fields by name or
 * full path, optional array-order and case insensitivity, numeric tolerance). Non-JSON bodies are compared as text.
 */
public final class JsonDiff {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonDiff() {
    }

    public static List<String> compare(String baselineBody, String targetBody, int baselineStatus, int targetStatus,
                                       CompareOptions options) {
        List<String> diffs = new ArrayList<>();
        if (baselineStatus != targetStatus) {
            diffs.add(String.format("HTTP status differs: baseline=%d, target=%d", baselineStatus, targetStatus));
        }
        if (baselineBody == null && targetBody == null) return diffs;
        CompareOptions o = options == null ? CompareOptions.defaults() : options;
        try {
            JsonNode source = parse(baselineBody);
            JsonNode target = parse(targetBody);
            double tolerance = o.numericTolerance() == null ? 0.001 : o.numericTolerance();
            diffNodes(source, target, "", new HashSet<>(o.ignoreFields()), o.caseSensitiveOn(),
                    Boolean.TRUE.equals(o.ignoreArrayOrder()), tolerance, diffs);
        } catch (Exception e) {
            if (!Objects.equals(baselineBody, targetBody)) {
                diffs.add("Response body differs (non-JSON): baseline and target values are different");
            }
        }
        return diffs;
    }

    private static void diffNodes(JsonNode source, JsonNode target, String path, Set<String> ignore,
                                  boolean caseSensitive, boolean ignoreArrayOrder, double tolerance, List<String> diffs) {
        if (shouldIgnore(path, ignore)) return;
        if (source == null && target == null) return;
        if (source == null) {
            diffs.add("Field missing in baseline: " + path);
            return;
        }
        if (target == null) {
            diffs.add("Field missing in target: " + path);
            return;
        }
        if (!source.getNodeType().equals(target.getNodeType())) {
            diffs.add(String.format("Type mismatch at [%s]: baseline=%s, target=%s", path, source.getNodeType(), target.getNodeType()));
            return;
        }
        switch (source.getNodeType()) {
            case OBJECT -> {
                Set<String> keys = new java.util.TreeSet<>();
                source.fieldNames().forEachRemaining(keys::add);
                target.fieldNames().forEachRemaining(keys::add);
                for (String key : keys) {
                    String child = path.isEmpty() ? key : path + "." + key;
                    diffNodes(source.get(key), target.get(key), child, ignore, caseSensitive, ignoreArrayOrder, tolerance, diffs);
                }
            }
            case ARRAY -> {
                if (source.size() != target.size()) {
                    diffs.add(String.format("Array length differs at [%s]: baseline=%d, target=%d", path, source.size(), target.size()));
                }
                if (ignoreArrayOrder) {
                    List<String> a = new ArrayList<>();
                    List<String> b = new ArrayList<>();
                    source.forEach(n -> a.add(n.toString()));
                    target.forEach(n -> b.add(n.toString()));
                    Collections.sort(a);
                    Collections.sort(b);
                    for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
                        if (!a.get(i).equals(b.get(i))) {
                            diffs.add(String.format("Array element mismatch at [%s] (order ignored): baseline has \"%s\", target has \"%s\"",
                                    path, a.get(i), b.get(i)));
                        }
                    }
                } else {
                    for (int i = 0; i < Math.min(source.size(), target.size()); i++) {
                        diffNodes(source.get(i), target.get(i), path + "[" + i + "]", ignore, caseSensitive, ignoreArrayOrder, tolerance, diffs);
                    }
                }
            }
            case NUMBER -> {
                if (Math.abs(source.asDouble() - target.asDouble()) > tolerance) {
                    diffs.add(String.format("Value differs at [%s]: baseline=%s, target=%s", path, source.asText(), target.asText()));
                }
            }
            case STRING -> {
                String sv = source.asText();
                String tv = target.asText();
                boolean equal = caseSensitive ? sv.equals(tv) : sv.equalsIgnoreCase(tv);
                if (!equal) diffs.add(String.format("Value differs at [%s]: baseline=\"%s\", target=\"%s\"", path, sv, tv));
            }
            default -> {
                if (!source.equals(target)) {
                    diffs.add(String.format("Value differs at [%s]: baseline=%s, target=%s", path, source.asText(), target.asText()));
                }
            }
        }
    }

    private static boolean shouldIgnore(String path, Set<String> ignore) {
        if (path.isEmpty() || ignore.isEmpty()) return false;
        String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
        int bracket = field.indexOf('[');
        String bare = bracket > 0 ? field.substring(0, bracket) : field;
        return ignore.contains(field) || ignore.contains(bare) || ignore.contains(path);
    }

    private static JsonNode parse(String json) throws Exception {
        if (json == null || json.isBlank()) return MAPPER.nullNode();
        return MAPPER.readTree(json);
    }
}
