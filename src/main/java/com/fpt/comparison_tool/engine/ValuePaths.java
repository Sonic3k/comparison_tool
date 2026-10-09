package com.fpt.comparison_tool.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Reads a value out of a response for variable extraction: $status, $header.Name, $ (whole body) or a JSON path
 * such as $.a.b[0].c, $.list[-1], $.list[key=value].field, $.list.length. Text nodes give their text, other nodes
 * their JSON; a missing or null value gives null.
 */
public final class ValuePaths {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ValuePaths() {
    }

    public static String read(String path, int status, Map<String, List<String>> headers, String body) {
        if (path == null) return null;
        String p = path.trim();
        if (p.equalsIgnoreCase("$status")) return String.valueOf(status);
        if (p.regionMatches(true, 0, "$header.", 0, 8)) {
            String name = p.substring(8);
            if (headers == null) return null;
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && e.getValue() != null && !e.getValue().isEmpty()) {
                    return String.join(", ", e.getValue());
                }
            }
            return null;
        }
        if (p.equals("$")) return body;
        if (!p.startsWith("$.") && !p.startsWith("$[")) return null;
        JsonNode root;
        try {
            if (body == null || body.isBlank()) return null;
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
        JsonNode node = resolve(root, p.substring(1));
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        return node.isTextual() ? node.asText() : node.toString();
    }

    static JsonNode resolve(JsonNode root, String path) {
        JsonNode current = root;
        int i = 0;
        int n = path.length();
        while (i < n && current != null) {
            char c = path.charAt(i);
            if (c == '.') {
                int j = i + 1;
                while (j < n && path.charAt(j) != '.' && path.charAt(j) != '[') j++;
                String field = path.substring(i + 1, j);
                if (field.equals("length") && (current.isArray() || current.isTextual())) {
                    current = MAPPER.getNodeFactory().numberNode(current.isArray() ? current.size() : current.asText().length());
                } else {
                    current = current.get(field);
                }
                i = j;
            } else if (c == '[') {
                int close = path.indexOf(']', i);
                if (close < 0) return null;
                String inside = path.substring(i + 1, close).trim();
                current = bracket(current, inside);
                i = close + 1;
            } else {
                return null;
            }
        }
        return current;
    }

    private static JsonNode bracket(JsonNode current, String inside) {
        if ((inside.startsWith("'") && inside.endsWith("'")) || (inside.startsWith("\"") && inside.endsWith("\""))) {
            return current.get(inside.substring(1, inside.length() - 1));
        }
        int eq = inside.indexOf('=');
        if (eq > 0) {
            if (!current.isArray()) return null;
            String key = inside.substring(0, eq).trim();
            String val = inside.substring(eq + 1).trim();
            if (val.length() >= 2 && (val.startsWith("\"") || val.startsWith("'"))) val = val.substring(1, val.length() - 1);
            for (JsonNode el : current) {
                JsonNode fv = el.get(key);
                if (fv != null && fv.asText().equals(val)) return el;
            }
            return null;
        }
        if (inside.equals("*")) return current.isArray() ? current : null;
        try {
            int idx = Integer.parseInt(inside);
            if (!current.isArray()) return null;
            return current.get(idx < 0 ? current.size() + idx : idx);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
