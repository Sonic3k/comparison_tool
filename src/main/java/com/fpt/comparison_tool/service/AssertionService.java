package com.fpt.comparison_tool.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates DSL assertions against a JSON response body.
 *
 * DSL syntax (one assertion per line):
 *   $.path == value
 *   $.path != value
 *   $.path exists
 *   $.path not exists
 *   $.path is null
 *   $.path not null
 *   $.path contains "string"
 *   $.path not contains "string"
 *   $.path > number
 *   $.path < number
 *   $.path >= number
 *   $.path <= number
 *   $.path.length == number
 *   $.path type == string|number|boolean|array|object
 *
 * Path uses dot notation: $.user.address.city, $.items[0].name (bracket index optional)
 */
@Service
public class AssertionService {

    private final ObjectMapper mapper = new ObjectMapper();

    public record AssertionLine(String raw, boolean passed, String reason) {}

    /** Run all assertions in automationConfig.expectedBody against targetBody + targetStatus. */
    public List<AssertionLine> evaluate(String expectedStatusDsl, String bodyDsl,
                                        String expectedHeaders,
                                        int actualStatus, long responseTimeMs,
                                        String targetBody, org.springframework.http.HttpHeaders headers) {
        List<AssertionLine> results = new ArrayList<>();

        // 1. Status assertion
        if (expectedStatusDsl != null && !expectedStatusDsl.isBlank()) {
            results.add(assertStatus(expectedStatusDsl.trim(), actualStatus));
        }

        // 2. Body assertions
        if (bodyDsl != null && !bodyDsl.isBlank()) {
            JsonNode root = null;
            try { root = mapper.readTree(targetBody); } catch (Exception ignored) {}
            for (String line : bodyDsl.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    results.add(evaluateLine(trimmed, root, targetBody));
                }
            }
        }

        return results;
    }

    // ── Status assertion ───────────────────────────────────────────────────────

    private AssertionLine assertStatus(String expected, int actual) {
        String raw = "status == " + expected;
        // Support wildcards: 2xx, 4xx, 5xx
        if (expected.endsWith("xx")) {
            int prefix = Integer.parseInt(expected.substring(0, 1));
            boolean pass = actual / 100 == prefix;
            return new AssertionLine(raw, pass, pass ? null : "expected " + expected + ", got " + actual);
        }
        try {
            int exp = Integer.parseInt(expected);
            boolean pass = exp == actual;
            return new AssertionLine(raw, pass, pass ? null : "expected " + exp + ", got " + actual);
        } catch (NumberFormatException e) {
            return new AssertionLine(raw, false, "invalid expected status: " + expected);
        }
    }

    // ── Body assertion line ────────────────────────────────────────────────────

    private AssertionLine evaluateLine(String line, JsonNode root, String rawBody) {
        try {
            // Parse: <path> <operator> [value]
            // Operators: ==, !=, exists, not exists, is null, not null,
            //            contains, not contains, >, <, >=, <=, type ==
            String path = null;
            String op = null;
            String rhs = null;

            // Remove leading $. if present
            String expr = line.startsWith("$.") ? line.substring(2) : line;

            // Try to split into path + op + rhs
            String[] parts = expr.split("\\s+", -1);
            if (parts.length >= 1) {
                // Find the path (first token)
                path = parts[0];

                if (parts.length == 1) {
                    op = "exists";
                } else if (parts.length == 2) {
                    op = parts[1].toLowerCase();
                } else if (parts.length >= 3) {
                    // Handle multi-word operators: "not exists", "is null", "not null", "not contains", "type =="
                    String op1 = parts[1].toLowerCase();
                    String op2 = parts[2].toLowerCase();
                    if (op1.equals("not") && (op2.equals("exists") || op2.equals("null") || op2.equals("contains"))) {
                        op = "not " + op2;
                        rhs = parts.length > 3 ? joinFrom(parts, 3) : null;
                    } else if (op1.equals("is") && op2.equals("null")) {
                        op = "is null";
                    } else if (op1.equals("type") && op2.equals("==")) {
                        op = "type ==";
                        rhs = parts.length > 3 ? joinFrom(parts, 3) : null;
                    } else {
                        op = op1;
                        rhs = joinFrom(parts, 2);
                    }
                }
            }

            if (path == null || op == null) {
                return new AssertionLine(line, false, "cannot parse assertion");
            }

            JsonNode node = root != null ? resolvePath(root, path) : null;
            return checkAssertion(line, node, op, rhs);

        } catch (Exception e) {
            return new AssertionLine(line, false, "error: " + e.getMessage());
        }
    }

    private AssertionLine checkAssertion(String raw, JsonNode node, String op, String rhs) {
        return switch (op) {
            case "exists"       -> result(raw, node != null && !node.isNull(), "field does not exist");
            case "not exists"   -> result(raw, node == null || node.isNull(), "field exists but should not");
            case "is null"      -> result(raw, node == null || node.isNull(), "field is not null, got: " + (node != null ? node.asText() : "missing"));
            case "not null"     -> result(raw, node != null && !node.isNull(), "field is null or missing");
            case "=="           -> assertEqual(raw, node, rhs);
            case "!="           -> assertNotEqual(raw, node, rhs);
            case "contains"     -> assertContains(raw, node, rhs, false);
            case "not contains" -> assertContains(raw, node, rhs, true);
            case ">"            -> assertNumeric(raw, node, rhs, ">") ;
            case ">="           -> assertNumeric(raw, node, rhs, ">=");
            case "<"            -> assertNumeric(raw, node, rhs, "<") ;
            case "<="           -> assertNumeric(raw, node, rhs, "<=");
            case "type =="      -> assertType(raw, node, rhs);
            default             -> new AssertionLine(raw, false, "unknown operator: " + op);
        };
    }

    private AssertionLine assertEqual(String raw, JsonNode node, String rhs) {
        if (node == null) return result(raw, false, "field missing");
        String actual = node.isTextual() ? node.asText() : node.toString();
        String expected = stripQuotes(rhs);
        // Try numeric equality with tolerance
        try {
            double a = Double.parseDouble(actual);
            double e = Double.parseDouble(expected);
            return result(raw, Math.abs(a - e) < 0.0001, "expected " + expected + ", got " + actual);
        } catch (NumberFormatException ignored) {}
        // Boolean
        if (expected.equalsIgnoreCase("true") || expected.equalsIgnoreCase("false")) {
            return result(raw, actual.equalsIgnoreCase(expected), "expected " + expected + ", got " + actual);
        }
        return result(raw, actual.equals(expected), "expected \"" + expected + "\", got \"" + actual + "\"");
    }

    private AssertionLine assertNotEqual(String raw, JsonNode node, String rhs) {
        if (node == null) return result(raw, true, null); // missing = not equal
        String actual = node.isTextual() ? node.asText() : node.toString();
        String expected = stripQuotes(rhs);
        return result(raw, !actual.equals(expected), "expected value to differ from \"" + expected + "\"");
    }

    private AssertionLine assertContains(String raw, JsonNode node, String rhs, boolean negate) {
        if (node == null) return result(raw, negate, "field missing");
        String actual = node.asText();
        String sub = stripQuotes(rhs);
        boolean contains = actual.contains(sub);
        if (negate) return result(raw, !contains, "expected not to contain \"" + sub + "\", but it did");
        return result(raw, contains, "expected to contain \"" + sub + "\", got \"" + actual + "\"");
    }

    private AssertionLine assertNumeric(String raw, JsonNode node, String rhs, String op) {
        if (node == null) return result(raw, false, "field missing");
        try {
            double actual   = node.asDouble();
            double expected = Double.parseDouble(stripQuotes(rhs));
            boolean pass = switch (op) {
                case ">"  -> actual > expected;
                case ">=" -> actual >= expected;
                case "<"  -> actual < expected;
                case "<=" -> actual <= expected;
                default   -> false;
            };
            return result(raw, pass, "expected " + actual + " " + op + " " + expected);
        } catch (NumberFormatException e) {
            return new AssertionLine(raw, false, "cannot parse number: " + rhs);
        }
    }

    private AssertionLine assertType(String raw, JsonNode node, String rhs) {
        if (node == null) return result(raw, false, "field missing");
        String expectedType = stripQuotes(rhs).toLowerCase();
        String actualType = switch (node.getNodeType()) {
            case STRING  -> "string";
            case NUMBER  -> "number";
            case BOOLEAN -> "boolean";
            case ARRAY   -> "array";
            case OBJECT  -> "object";
            case NULL    -> "null";
            default      -> "unknown";
        };
        return result(raw, actualType.equals(expectedType),
                "expected type " + expectedType + ", got " + actualType);
    }

    // ── Path resolution ────────────────────────────────────────────────────────

    /**
     * Resolve dot-notation path against JSON root.
     * Supports: field, field.sub, field.length (array length), field[0]
     */
    private JsonNode resolvePath(JsonNode root, String path) {
        if (path == null || path.isBlank()) return root;
        String[] segments = path.split("\\.");
        JsonNode current = root;
        for (String seg : segments) {
            if (current == null) return null;
            // Handle array index: items[0]
            if (seg.contains("[") && seg.endsWith("]")) {
                int bIdx = seg.indexOf('[');
                String field = seg.substring(0, bIdx);
                int idx = Integer.parseInt(seg.substring(bIdx + 1, seg.length() - 1));
                if (!field.isEmpty()) current = current.get(field);
                if (current != null && current.isArray()) current = current.get(idx);
            } else if (seg.equals("length") && current != null && (current.isArray() || current.isTextual())) {
                // $.items.length → return size as number node
                return mapper.getNodeFactory().numberNode(current.isArray() ? current.size() : current.asText().length());
            } else {
                current = current.get(seg);
            }
        }
        return current;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private AssertionLine result(String raw, boolean passed, String failReason) {
        return new AssertionLine(raw, passed, passed ? null : failReason);
    }

    private String stripQuotes(String s) {
        if (s == null) return "";
        String t = s.trim();
        if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))
            return t.substring(1, t.length() - 1);
        return t;
    }

    private String joinFrom(String[] parts, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    /** Summarize assertion results into a single string for Excel/UI display. */
    public String summarize(List<AssertionLine> lines) {
        if (lines == null || lines.isEmpty()) return "";
        long passed = lines.stream().filter(AssertionLine::passed).count();
        long total  = lines.size();
        if (passed == total) return passed + "/" + total + " passed";

        StringBuilder sb = new StringBuilder(passed + "/" + total + " passed");
        for (AssertionLine l : lines) {
            if (!l.passed()) {
                sb.append("\n✗ ").append(l.raw());
                if (l.reason() != null) sb.append(" → ").append(l.reason());
            }
        }
        return sb.toString();
    }

    /** Whether all assertions passed. */
    public boolean allPassed(List<AssertionLine> lines) {
        return lines != null && !lines.isEmpty() && lines.stream().allMatch(AssertionLine::passed);
    }
}
