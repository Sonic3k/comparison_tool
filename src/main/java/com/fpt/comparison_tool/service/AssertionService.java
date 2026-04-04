package com.fpt.comparison_tool.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates DSL assertions against a JSON response body.
 *
 * ── Scalar assertions ──────────────────────────────────────────────────
 *   $.path == value
 *   $.path != value
 *   $.path exists / not exists
 *   $.path is null / not null
 *   $.path contains "string" / not contains "string"
 *   $.path > / >= / < / <= number
 *   $.path.length == number
 *   $.path type == string|number|boolean|array|object
 *
 * ── Array wildcard [*] ─────────────────────────────────────────────────
 *   $.orders[*].order_id contains "ORD-10001"
 *     → PASS if ANY element has order_id == "ORD-10001"
 *
 *   $.orders[*].currency all == "USD"
 *     → PASS if ALL elements have currency == "USD"
 *
 *   $.orders[*].order_status any == "confirmed"
 *     → PASS if AT LEAST ONE element has order_status == "confirmed"
 *
 * ── Array filter [key=value] ───────────────────────────────────────────
 *   $.orders[order_id="ORD-10001"].order_status == "confirmed"
 *     → find element where order_id == "ORD-10001", assert its order_status
 *
 *   $.orders[order_id="ORD-10001"].total_amount > 100
 *   $.orders[user_id=1].order_status exists
 */
@Service
public class AssertionService {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final Pattern FILTER_PATTERN =
            Pattern.compile("^(.+?)\\[([\\w_]+)=[\"']?([^\"'\\]]+)[\"']?](.*)$");

    public record AssertionLine(String raw, boolean passed, String reason) {}

    public List<AssertionLine> evaluate(String expectedStatusDsl, String bodyDsl,
                                        String expectedHeaders,
                                        int actualStatus, long responseTimeMs,
                                        String targetBody,
                                        org.springframework.http.HttpHeaders headers) {
        List<AssertionLine> results = new ArrayList<>();

        if (expectedStatusDsl != null && !expectedStatusDsl.isBlank()) {
            results.add(assertStatus(expectedStatusDsl.trim(), actualStatus));
        }

        if (bodyDsl != null && !bodyDsl.isBlank()) {
            JsonNode root = null;
            try { root = mapper.readTree(targetBody); } catch (Exception ignored) {}
            for (String line : bodyDsl.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) results.add(evaluateLine(trimmed, root));
            }
        }

        return results;
    }

    // ── Status ────────────────────────────────────────────────────────────────

    private AssertionLine assertStatus(String expected, int actual) {
        String raw = "status == " + expected;
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

    // ── Main dispatch ─────────────────────────────────────────────────────────

    private AssertionLine evaluateLine(String line, JsonNode root) {
        try {
            String expr = line.startsWith("$.") ? line.substring(2) : line;

            // 1. Array filter: path[key="value"].field op rhs
            Matcher filterMatcher = FILTER_PATTERN.matcher(expr);
            if (filterMatcher.matches()) {
                return evaluateFilterAssertion(line, filterMatcher, root);
            }

            // 2. Array wildcard: path[*].field op rhs
            if (expr.contains("[*].")) {
                return evaluateWildcardAssertion(line, expr, root);
            }

            // 3. Scalar
            return evaluateScalar(line, expr, root);

        } catch (Exception e) {
            return new AssertionLine(line, false, "error: " + e.getMessage());
        }
    }

    // ── Array filter ──────────────────────────────────────────────────────────
    // $.orders[order_id="ORD-10001"].order_status == "confirmed"

    private AssertionLine evaluateFilterAssertion(String raw, Matcher m, JsonNode root) {
        String arrayPath = m.group(1);
        String filterKey = m.group(2);
        String filterVal = m.group(3);
        String remainder = m.group(4); // ".field op rhs" or "" for exists

        if (root == null) return new AssertionLine(raw, false, "response is not valid JSON");

        JsonNode array = resolvePath(root, arrayPath);
        if (array == null || !array.isArray()) {
            return new AssertionLine(raw, false,
                    "'" + arrayPath + "' is not an array or does not exist");
        }

        List<JsonNode> matched = new ArrayList<>();
        for (JsonNode el : array) {
            JsonNode fv = el.get(filterKey);
            if (fv != null && fv.asText().equals(filterVal)) matched.add(el);
        }

        if (matched.isEmpty()) {
            return new AssertionLine(raw, false,
                    "no element found where " + filterKey + " == \"" + filterVal + "\"");
        }

        // Parse field + op + rhs from remainder
        if (remainder == null || remainder.isBlank()) {
            // No further assertion → just checking the element exists
            return new AssertionLine(raw, true, null);
        }

        String fieldExpr = remainder.startsWith(".") ? remainder.substring(1) : remainder;
        ParsedOp parsed = parseOp(fieldExpr.split("\\s+", -1), 0);

        List<String> failures = new ArrayList<>();
        for (JsonNode el : matched) {
            JsonNode node = parsed.field.isEmpty() ? el : resolvePath(el, parsed.field);
            AssertionLine r = checkAssertion(raw, node, parsed.op, parsed.rhs);
            if (!r.passed()) failures.add(r.reason());
        }

        if (failures.isEmpty()) return new AssertionLine(raw, true, null);
        return new AssertionLine(raw, false,
                "filter [" + filterKey + "=\"" + filterVal + "\"]: " + failures.get(0));
    }

    // ── Array wildcard ────────────────────────────────────────────────────────
    // $.orders[*].currency all == "USD"
    // $.orders[*].order_id contains "ORD-10001"
    // $.orders[*].order_status any == "confirmed"

    private AssertionLine evaluateWildcardAssertion(String raw, String expr, JsonNode root) {
        int wildIdx = expr.indexOf("[*].");
        String arrayPath = expr.substring(0, wildIdx);
        String afterWild = expr.substring(wildIdx + 4); // "currency all == \"USD\""

        if (root == null) return new AssertionLine(raw, false, "response is not valid JSON");

        JsonNode array = resolvePath(root, arrayPath);
        if (array == null || !array.isArray()) {
            return new AssertionLine(raw, false,
                    "'" + arrayPath + "' is not an array or does not exist");
        }
        if (array.size() == 0) {
            return new AssertionLine(raw, false, "'" + arrayPath + "' is empty");
        }

        String[] parts = afterWild.split("\\s+", -1);

        // Detect quantifier: "field all op rhs" or "field any op rhs" or "field op rhs"
        String fieldPath;
        String quantifier;
        String op;
        String rhs;

        if (parts.length >= 2 &&
                (parts[1].equalsIgnoreCase("all") || parts[1].equalsIgnoreCase("any"))) {
            fieldPath  = parts[0];
            quantifier = parts[1].toLowerCase();
            ParsedOp parsed = parseOp(parts, 2);
            op  = parsed.op;
            rhs = parsed.rhs;
        } else {
            fieldPath = parts[0];
            ParsedOp parsed = parseOp(parts, 1);
            op  = parsed.op;
            rhs = parsed.rhs;
            // "contains" without explicit quantifier = any-match semantics
            quantifier = (op.equals("contains") || op.equals("any")) ? "any" : "all";
        }

        int passCount = 0, total = array.size();
        String firstFailReason = null;

        for (JsonNode el : array) {
            JsonNode node = resolvePath(el, fieldPath);
            AssertionLine r = checkAssertion(raw, node, op, rhs);
            if (r.passed()) passCount++;
            else if (firstFailReason == null) firstFailReason = r.reason();
        }

        boolean pass = quantifier.equals("all") ? passCount == total : passCount > 0;
        if (pass) return new AssertionLine(raw, true, null);

        String hint = quantifier.equals("all")
                ? passCount + "/" + total + " elements passed — " + firstFailReason
                : "0/" + total + " elements matched — " + firstFailReason;
        return new AssertionLine(raw, false, hint);
    }

    // ── Scalar (unchanged) ────────────────────────────────────────────────────

    private AssertionLine evaluateScalar(String line, String expr, JsonNode root) {
        String[] parts = expr.split("\\s+", -1);
        String path = parts[0];
        ParsedOp parsed = parseOp(parts, 1);
        JsonNode node = root != null ? resolvePath(root, path) : null;
        return checkAssertion(line, node, parsed.op, parsed.rhs);
    }

    // ── Op parser helper ──────────────────────────────────────────────────────

    private record ParsedOp(String field, String op, String rhs) {}

    private ParsedOp parseOp(String[] parts, int from) {
        if (from >= parts.length) return new ParsedOp("", "exists", null);

        // Check if parts[from] is a field name (not an operator keyword)
        // This is used when the field is parsed separately before calling
        String firstOp = parts[from].toLowerCase();

        if (from + 1 < parts.length) {
            String op1 = parts[from].toLowerCase();
            String op2 = parts[from + 1].toLowerCase();
            if (op1.equals("not") && (op2.equals("exists") || op2.equals("null") || op2.equals("contains"))) {
                return new ParsedOp("", "not " + op2,
                        from + 2 < parts.length ? joinFrom(parts, from + 2) : null);
            }
            if (op1.equals("is") && op2.equals("null")) {
                return new ParsedOp("", "is null", null);
            }
            if (op1.equals("type") && op2.equals("==")) {
                return new ParsedOp("", "type ==",
                        from + 2 < parts.length ? joinFrom(parts, from + 2) : null);
            }
        }

        String op  = firstOp;
        String rhs = from + 1 < parts.length ? joinFrom(parts, from + 1) : null;
        return new ParsedOp("", op, rhs);
    }

    // Use this variant when first token of slice is the field name
    private ParsedOp parseOpWithField(String[] parts, int fieldIdx) {
        if (fieldIdx >= parts.length) return new ParsedOp("", "exists", null);
        String field = parts[fieldIdx];
        ParsedOp rest = parseOp(parts, fieldIdx + 1);
        return new ParsedOp(field, rest.op, rest.rhs);
    }

    // ── Core check ────────────────────────────────────────────────────────────

    private AssertionLine checkAssertion(String raw, JsonNode node, String op, String rhs) {
        return switch (op) {
            case "exists"       -> result(raw, node != null && !node.isNull(), "field does not exist");
            case "not exists"   -> result(raw, node == null || node.isNull(), "field exists but should not");
            case "is null"      -> result(raw, node == null || node.isNull(),
                                          "not null, got: " + (node != null ? node.asText() : "missing"));
            case "not null"     -> result(raw, node != null && !node.isNull(), "field is null or missing");
            case "=="           -> assertEqual(raw, node, rhs);
            case "!="           -> assertNotEqual(raw, node, rhs);
            case "contains"     -> assertContains(raw, node, rhs, false);
            case "not contains" -> assertContains(raw, node, rhs, true);
            case ">"            -> assertNumeric(raw, node, rhs, ">");
            case ">="           -> assertNumeric(raw, node, rhs, ">=");
            case "<"            -> assertNumeric(raw, node, rhs, "<");
            case "<="           -> assertNumeric(raw, node, rhs, "<=");
            case "type =="      -> assertType(raw, node, rhs);
            default             -> new AssertionLine(raw, false, "unknown operator: " + op);
        };
    }

    private AssertionLine assertEqual(String raw, JsonNode node, String rhs) {
        if (node == null) return result(raw, false, "field missing");
        String actual = node.isTextual() ? node.asText() : node.toString();
        String expected = stripQuotes(rhs);
        try {
            double a = Double.parseDouble(actual), e = Double.parseDouble(expected);
            return result(raw, Math.abs(a - e) < 0.0001, "expected " + expected + ", got " + actual);
        } catch (NumberFormatException ignored) {}
        if (expected.equalsIgnoreCase("true") || expected.equalsIgnoreCase("false"))
            return result(raw, actual.equalsIgnoreCase(expected), "expected " + expected + ", got " + actual);
        return result(raw, actual.equals(expected), "expected \"" + expected + "\", got \"" + actual + "\"");
    }

    private AssertionLine assertNotEqual(String raw, JsonNode node, String rhs) {
        if (node == null) return result(raw, true, null);
        String actual = node.isTextual() ? node.asText() : node.toString();
        return result(raw, !actual.equals(stripQuotes(rhs)),
                "expected value to differ from \"" + stripQuotes(rhs) + "\"");
    }

    private AssertionLine assertContains(String raw, JsonNode node, String rhs, boolean negate) {
        if (node == null) return result(raw, negate, "field missing");
        String actual = node.asText(), sub = stripQuotes(rhs);
        boolean contains = actual.contains(sub);
        if (negate) return result(raw, !contains, "expected not to contain \"" + sub + "\", but it did");
        return result(raw, contains, "expected to contain \"" + sub + "\", got \"" + actual + "\"");
    }

    private AssertionLine assertNumeric(String raw, JsonNode node, String rhs, String op) {
        if (node == null) return result(raw, false, "field missing");
        try {
            double actual = node.asDouble(), expected = Double.parseDouble(stripQuotes(rhs));
            boolean pass = switch (op) {
                case ">"  -> actual > expected;
                case ">=" -> actual >= expected;
                case "<"  -> actual < expected;
                case "<=" -> actual <= expected;
                default   -> false;
            };
            return result(raw, pass, actual + " " + op + " " + expected + " is false");
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

    // ── Path resolution ───────────────────────────────────────────────────────

    private JsonNode resolvePath(JsonNode root, String path) {
        if (path == null || path.isBlank()) return root;
        String[] segments = path.split("\\.");
        JsonNode current = root;
        for (String seg : segments) {
            if (current == null) return null;
            if (seg.contains("[") && seg.endsWith("]")) {
                int bIdx = seg.indexOf('[');
                String field  = seg.substring(0, bIdx);
                String idxStr = seg.substring(bIdx + 1, seg.length() - 1);
                if (!field.isEmpty()) current = current.get(field);
                try {
                    int idx = Integer.parseInt(idxStr);
                    if (current != null && current.isArray()) current = current.get(idx);
                } catch (NumberFormatException ignored) { current = null; }
            } else if (seg.equals("length") && current != null
                    && (current.isArray() || current.isTextual())) {
                return mapper.getNodeFactory().numberNode(
                        current.isArray() ? current.size() : current.asText().length());
            } else {
                current = current.get(seg);
            }
        }
        return current;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    public boolean allPassed(List<AssertionLine> lines) {
        return lines != null && !lines.isEmpty() && lines.stream().allMatch(AssertionLine::passed);
    }
}
