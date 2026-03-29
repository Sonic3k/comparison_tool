package com.fpt.comparison_tool.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.comparison_tool.model.ComparisonConfig;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ComparisonService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Compares two JSON response bodies.
     *
     * @return list of human-readable difference descriptions, empty = identical
     */
    public List<String> compare(String sourceBody, String targetBody,
                                int sourceStatus, int targetStatus,
                                ComparisonConfig config) {
        List<String> diffs = new ArrayList<>();

        // 1. Status code check
        if (sourceStatus != targetStatus) {
            diffs.add(String.format("HTTP status differs: source=%d, target=%d",
                    sourceStatus, targetStatus));
        }

        // 2. JSON body diff
        if (sourceBody == null && targetBody == null) return diffs;

        try {
            JsonNode source = parse(sourceBody);
            JsonNode target = parse(targetBody);
            Set<String> ignoreFields = new HashSet<>(config != null
                    ? config.getIgnoreFieldsAsList()
                    : Collections.emptyList());
            boolean caseSensitive = config == null || config.isCaseSensitive();
            double tolerance      = config != null ? config.getNumericTolerance() : 0.001;

            diffNodes(source, target, "", ignoreFields, caseSensitive, tolerance, diffs);
        } catch (Exception e) {
            // If not valid JSON, do plain string comparison
            if (!Objects.equals(sourceBody, targetBody)) {
                diffs.add("Response body differs (non-JSON): source and target values are different");
            }
        }

        return diffs;
    }

    // ─── Recursive JSON diff ──────────────────────────────────────────────────

    private void diffNodes(JsonNode source, JsonNode target, String path,
                           Set<String> ignore, boolean caseSensitive, double tolerance,
                           List<String> diffs) {
        if (shouldIgnore(path, ignore)) return;

        if (source == null && target == null) return;

        if (source == null) { diffs.add("Field missing in source: " + path); return; }
        if (target == null) { diffs.add("Field missing in target: " + path); return; }

        if (!source.getNodeType().equals(target.getNodeType())) {
            diffs.add(String.format("Type mismatch at [%s]: source=%s, target=%s",
                    path, source.getNodeType(), target.getNodeType()));
            return;
        }

        switch (source.getNodeType()) {
            case OBJECT -> {
                Set<String> allKeys = new HashSet<>();
                source.fieldNames().forEachRemaining(allKeys::add);
                target.fieldNames().forEachRemaining(allKeys::add);
                for (String key : allKeys) {
                    String childPath = path.isEmpty() ? key : path + "." + key;
                    diffNodes(source.get(key), target.get(key), childPath, ignore,
                              caseSensitive, tolerance, diffs);
                }
            }
            case ARRAY -> {
                boolean ignoreOrder = config != null && config.isIgnoreArrayOrder();

                if (source.size() != target.size()) {
                    diffs.add(String.format("Array length differs at [%s]: source=%d, target=%d",
                            path, source.size(), target.size()));
                    // Still compare what we can
                }

                if (ignoreOrder) {
                    // Convert both arrays to sorted string representations and match
                    List<String> srcList = new ArrayList<>();
                    List<String> tgtList = new ArrayList<>();
                    source.forEach(n -> srcList.add(n.toString()));
                    target.forEach(n -> tgtList.add(n.toString()));
                    Collections.sort(srcList);
                    Collections.sort(tgtList);

                    for (int i = 0; i < Math.min(srcList.size(), tgtList.size()); i++) {
                        if (!srcList.get(i).equals(tgtList.get(i))) {
                            diffs.add(String.format(
                                "Array element mismatch at [%s] (order ignored): source has \"%s\", target has \"%s\"",
                                path, srcList.get(i), tgtList.get(i)));
                        }
                    }
                } else {
                    int len = Math.min(source.size(), target.size());
                    for (int i = 0; i < len; i++) {
                        diffNodes(source.get(i), target.get(i),
                                  path + "[" + i + "]", ignore, caseSensitive, tolerance, diffs);
                    }
                }
            }
            case NUMBER -> {
                double sv = source.asDouble();
                double tv = target.asDouble();
                if (Math.abs(sv - tv) > tolerance) {
                    diffs.add(String.format("Value differs at [%s]: source=%s, target=%s",
                            path, source.asText(), target.asText()));
                }
            }
            case STRING -> {
                String sv = source.asText();
                String tv = target.asText();
                boolean equal = caseSensitive ? sv.equals(tv) : sv.equalsIgnoreCase(tv);
                if (!equal) {
                    diffs.add(String.format("Value differs at [%s]: source=\"%s\", target=\"%s\"",
                            path, sv, tv));
                }
            }
            default -> {
                if (!source.equals(target)) {
                    diffs.add(String.format("Value differs at [%s]: source=%s, target=%s",
                            path, source.asText(), target.asText()));
                }
            }
        }
    }

    private boolean shouldIgnore(String path, Set<String> ignoreFields) {
        if (path.isEmpty() || ignoreFields.isEmpty()) return false;
        String fieldName = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
        return ignoreFields.contains(fieldName) || ignoreFields.contains(path);
    }

    private JsonNode parse(String json) throws Exception {
        if (json == null || json.isBlank()) return objectMapper.nullNode();
        return objectMapper.readTree(json);
    }
}