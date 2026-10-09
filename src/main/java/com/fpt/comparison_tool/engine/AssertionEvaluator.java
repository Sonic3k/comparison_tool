package com.fpt.comparison_tool.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fpt.comparison_tool.result.AssertionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Evaluates the assertion DSL against a response. One assertion per line:
 * <pre>
 *   $.path == value | != value | exists | not exists | is null | not null
 *   $.path contains "text" | not contains "text" | matches "regex" | not matches "regex"
 *   $.path > n | >= n | < n | <= n        $.path.length == n        $.path type == string|number|boolean|array|object
 *   $.items[*].field all == "x" | any == "x" | contains "x"      $.items[*] any == "x" (terminal [*] needs any/all)
 *   $.items[key="value"].field == "x"     header Content-Type contains "json"
 * </pre>
 * Status expectations: 200, 2xx, or a comma list such as 200,404.
 */
public final class AssertionEvaluator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern FILTER_PATTERN = Pattern.compile("^(.+?)\\[([\\w_]+)=[\"']?([^\"'\\]]+)[\"']?](.*)$");
    private static final Pattern TERMINAL_WILDCARD = Pattern.compile("^(.+)\\[\\*]\\s*(.*)$");

    private AssertionEvaluator() {
    }

    // ---------------------------------------------------------------- status

    public static AssertionResult status(String expected, int actual) {
        String exp = expected == null ? "" : expected.replace(" ", "");
        String raw = "status == " + exp;
        if (exp.isEmpty()) return new AssertionResult(raw, true, null);
        if (exp.startsWith("<")) {
            try {
                int limit = Integer.parseInt(exp.substring(1));
                boolean pass = actual < limit;
                return new AssertionResult("status < " + limit, pass, pass ? null : "got " + actual);
            } catch (NumberFormatException e) {
                return new AssertionResult(raw, false, "invalid expected status: " + expected);
            }
        }
        for (String part : exp.split(",")) {
            if (part.isEmpty()) continue;
            if (part.length() == 3 && part.toLowerCase(Locale.ROOT).endsWith("xx") && Character.isDigit(part.charAt(0))) {
                if (actual / 100 == part.charAt(0) - '0') return new AssertionResult(raw, true, null);
                continue;
            }
            try {
                if (Integer.parseInt(part) == actual) return new AssertionResult(raw, true, null);
            } catch (NumberFormatException e) {
                return new AssertionResult(raw, false, "invalid expected status: " + expected);
            }
        }
        return new AssertionResult(raw, false, "expected " + exp + ", got " + actual);
    }

    public static AssertionResult time(int maxMs, long actualMs) {
        boolean pass = actualMs <= maxMs;
        return new AssertionResult("time <= " + maxMs + " ms", pass, pass ? null : "took " + actualMs + " ms");
    }

    // ---------------------------------------------------------------- body / headers

    public static List<AssertionResult> evaluate(List<String> lines, String body, Map<String, List<String>> headers) {
        List<AssertionResult> out = new ArrayList<>();
        if (lines == null || lines.isEmpty()) return out;
        JsonNode root = parse(body);
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) continue;
            out.add(evaluateLine(trimmed, root, headers));
        }
        return out;
    }

    /** Returns null when the line is understood, otherwise a short explanation (used by suite validation). */
    public static String checkSyntax(String line) {
        String t = line == null ? "" : line.trim();
        if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) return null;
        if (t.regionMatches(true, 0, "header ", 0, 7)) {
            String[] p = t.substring(7).trim().split("\\s+", -1);
            return p.length >= 1 && !p[0].isEmpty() ? checkOp(p, 1) : "header assertion needs a header name";
        }
        if (!t.startsWith("$")) return "an assertion starts with $. or header";
        String expr = t.startsWith("$.") ? t.substring(2) : t.substring(1);
        Matcher f = FILTER_PATTERN.matcher(expr);
        if (f.matches()) {
            String rem = f.group(4);
            if (rem == null || rem.isBlank()) return null;
            String[] p = (rem.startsWith(".") ? rem.substring(1) : rem).split("\\s+", -1);
            return checkOp(p, 1);
        }
        if (expr.contains("[*].")) {
            String[] p = expr.substring(expr.indexOf("[*].") + 4).split("\\s+", -1);
            int from = p.length >= 2 && (p[1].equalsIgnoreCase("all") || p[1].equalsIgnoreCase("any")) ? 2 : 1;
            return checkOp(p, from);
        }
        Matcher tw = TERMINAL_WILDCARD.matcher(expr);
        if (tw.matches()) {
            String rest = tw.group(2) == null ? "" : tw.group(2).trim();
            String[] p = rest.isEmpty() ? new String[0] : rest.split("\\s+", -1);
            if (p.length == 0 || !(p[0].equalsIgnoreCase("any") || p[0].equalsIgnoreCase("all"))) {
                return "terminal [*] needs any or all, e.g. $.list[*] any == \"x\"";
            }
            return checkOp(p, 1);
        }
        return checkOp(expr.split("\\s+", -1), 1);
    }

    private static String checkOp(String[] parts, int from) {
        ParsedOp op = parseOp(parts, from);
        return switch (op.op) {
            case "exists", "not exists", "is null", "not null" -> null;
            case "==", "!=", "contains", "not contains", "matches", "not matches", ">", ">=", "<", "<=", "type ==" ->
                    op.rhs == null || op.rhs.isBlank() ? "operator " + op.op + " needs a value" : null;
            default -> "unknown operator: " + op.op;
        };
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static AssertionResult evaluateLine(String line, JsonNode root, Map<String, List<String>> headers) {
        try {
            if (line.regionMatches(true, 0, "header ", 0, 7)) return evaluateHeader(line, headers);
            String expr = line.startsWith("$.") ? line.substring(2) : (line.startsWith("$") ? line.substring(1) : line);

            Matcher filterMatcher = FILTER_PATTERN.matcher(expr);
            if (filterMatcher.matches()) return evaluateFilter(line, filterMatcher, root);
            if (expr.contains("[*].")) return evaluateWildcard(line, expr, root);
            Matcher termWild = TERMINAL_WILDCARD.matcher(expr);
            if (termWild.matches()) return evaluateTerminalWildcard(line, termWild, root);
            return evaluateScalar(line, expr, root);
        } catch (Exception e) {
            return new AssertionResult(line, false, "error: " + e.getMessage());
        }
    }

    private static AssertionResult evaluateHeader(String line, Map<String, List<String>> headers) {
        String[] parts = line.substring(7).trim().split("\\s+", -1);
        String name = parts[0];
        String value = null;
        if (headers != null) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && e.getValue() != null && !e.getValue().isEmpty()) {
                    value = String.join(", ", e.getValue());
                    break;
                }
            }
        }
        ParsedOp op = parseOp(parts, 1);
        return check(line, value == null ? null : TextNode.valueOf(value), op.op, op.rhs);
    }

    // $.orders[order_id="ORD-1"].status == "confirmed"
    private static AssertionResult evaluateFilter(String raw, Matcher m, JsonNode root) {
        String arrayPath = m.group(1);
        String filterKey = m.group(2);
        String filterVal = m.group(3);
        String remainder = m.group(4);
        if (root == null) return new AssertionResult(raw, false, "response is not valid JSON");
        JsonNode array = resolvePath(root, arrayPath);
        if (array == null || !array.isArray()) {
            return new AssertionResult(raw, false, "'" + arrayPath + "' is not an array or does not exist");
        }
        List<JsonNode> matched = new ArrayList<>();
        for (JsonNode el : array) {
            JsonNode fv = el.get(filterKey);
            if (fv != null && fv.asText().equals(filterVal)) matched.add(el);
        }
        if (matched.isEmpty()) {
            return new AssertionResult(raw, false, "no element found where " + filterKey + " == \"" + filterVal + "\"");
        }
        if (remainder == null || remainder.isBlank()) return new AssertionResult(raw, true, null);
        String fieldExpr = remainder.startsWith(".") ? remainder.substring(1) : remainder;
        String[] parts = fieldExpr.split("\\s+", -1);
        String fieldPath = parts[0];
        ParsedOp parsed = parseOp(parts, 1);
        List<String> failures = new ArrayList<>();
        for (JsonNode el : matched) {
            JsonNode node = fieldPath.isBlank() ? el : resolvePath(el, fieldPath);
            AssertionResult r = check(raw, node, parsed.op, parsed.rhs);
            if (!r.pass()) failures.add(r.message());
        }
        if (failures.isEmpty()) return new AssertionResult(raw, true, null);
        return new AssertionResult(raw, false, "filter [" + filterKey + "=\"" + filterVal + "\"]: " + failures.get(0));
    }

    // $.orders[*].currency all == "USD" | any == "x" | contains "x"
    private static AssertionResult evaluateWildcard(String raw, String expr, JsonNode root) {
        int wildIdx = expr.indexOf("[*].");
        String arrayPath = expr.substring(0, wildIdx);
        String afterWild = expr.substring(wildIdx + 4);
        if (root == null) return new AssertionResult(raw, false, "response is not valid JSON");
        JsonNode array = resolvePath(root, arrayPath);
        if (array == null || !array.isArray()) {
            return new AssertionResult(raw, false, "'" + arrayPath + "' is not an array or does not exist");
        }
        if (array.isEmpty()) return new AssertionResult(raw, false, "'" + arrayPath + "' is empty");

        String[] parts = afterWild.split("\\s+", -1);
        String fieldPath = parts[0];
        String quantifier;
        ParsedOp parsed;
        if (parts.length >= 2 && (parts[1].equalsIgnoreCase("all") || parts[1].equalsIgnoreCase("any"))) {
            quantifier = parts[1].toLowerCase(Locale.ROOT);
            parsed = parseOp(parts, 2);
        } else {
            parsed = parseOp(parts, 1);
            quantifier = (parsed.op.equals("contains") || parsed.op.equals("any")) ? "any" : "all";
        }
        return quantified(raw, array, fieldPath, quantifier, parsed);
    }

    // $.to[*] any == "a@x.com"
    private static AssertionResult evaluateTerminalWildcard(String raw, Matcher m, JsonNode root) {
        String arrayPath = m.group(1);
        String rest = m.group(2) == null ? "" : m.group(2).trim();
        String[] tokens = rest.isEmpty() ? new String[0] : rest.split("\\s+", -1);
        if (tokens.length == 0 || !(tokens[0].equalsIgnoreCase("any") || tokens[0].equalsIgnoreCase("all"))) {
            return new AssertionResult(raw, false,
                    "terminal [*] requires explicit quantifier: use 'any' or 'all' (e.g. $.to[*] any == \"x\")");
        }
        String quantifier = tokens[0].toLowerCase(Locale.ROOT);
        ParsedOp parsed = parseOp(tokens, 1);
        if (root == null) return new AssertionResult(raw, false, "response is not valid JSON");
        JsonNode array = resolvePath(root, arrayPath);
        if (array == null || !array.isArray()) {
            return new AssertionResult(raw, false, "'" + arrayPath + "' is not an array or does not exist");
        }
        if (array.isEmpty()) return new AssertionResult(raw, false, "'" + arrayPath + "' is empty");
        return quantified(raw, array, null, quantifier, parsed);
    }

    private static AssertionResult quantified(String raw, JsonNode array, String fieldPath, String quantifier, ParsedOp parsed) {
        int passCount = 0;
        int total = array.size();
        String firstFail = null;
        for (JsonNode el : array) {
            JsonNode node = fieldPath == null ? el : resolvePath(el, fieldPath);
            AssertionResult r = check(raw, node, parsed.op, parsed.rhs);
            if (r.pass()) passCount++;
            else if (firstFail == null) firstFail = r.message();
        }
        boolean pass = quantifier.equals("all") ? passCount == total : passCount > 0;
        if (pass) return new AssertionResult(raw, true, null);
        String hint = quantifier.equals("all")
                ? passCount + "/" + total + " elements passed - " + firstFail
                : "0/" + total + " elements matched - " + firstFail;
        return new AssertionResult(raw, false, hint);
    }

    private static AssertionResult evaluateScalar(String line, String expr, JsonNode root) {
        String[] parts = expr.split("\\s+", -1);
        ParsedOp parsed = parseOp(parts, 1);
        JsonNode node = root != null ? resolvePath(root, parts[0]) : null;
        return check(line, node, parsed.op, parsed.rhs);
    }

    // ---------------------------------------------------------------- operators

    private record ParsedOp(String op, String rhs) {
    }

    private static ParsedOp parseOp(String[] parts, int from) {
        if (from >= parts.length) return new ParsedOp("exists", null);
        if (from + 1 < parts.length) {
            String op1 = parts[from].toLowerCase(Locale.ROOT);
            String op2 = parts[from + 1].toLowerCase(Locale.ROOT);
            if (op1.equals("not") && (op2.equals("exists") || op2.equals("null") || op2.equals("contains") || op2.equals("matches"))) {
                return new ParsedOp("not " + op2, from + 2 < parts.length ? joinFrom(parts, from + 2) : null);
            }
            if (op1.equals("is") && op2.equals("null")) return new ParsedOp("is null", null);
            if (op1.equals("type") && op2.equals("==")) {
                return new ParsedOp("type ==", from + 2 < parts.length ? joinFrom(parts, from + 2) : null);
            }
        }
        String op = parts[from].toLowerCase(Locale.ROOT);
        String rhs = from + 1 < parts.length ? joinFrom(parts, from + 1) : null;
        return new ParsedOp(op, rhs);
    }

    private static AssertionResult check(String raw, JsonNode node, String op, String rhs) {
        return switch (op) {
            case "exists" -> result(raw, node != null && !node.isNull(), "field does not exist");
            case "not exists" -> result(raw, node == null || node.isNull(), "field exists but should not");
            case "is null" -> result(raw, node == null || node.isNull(), "not null, got: " + (node != null ? node.asText() : "missing"));
            case "not null" -> result(raw, node != null && !node.isNull(), "field is null or missing");
            case "==" -> assertEqual(raw, node, rhs);
            case "!=" -> assertNotEqual(raw, node, rhs);
            case "contains" -> assertContains(raw, node, rhs, false);
            case "not contains" -> assertContains(raw, node, rhs, true);
            case "matches" -> assertMatches(raw, node, rhs, false);
            case "not matches" -> assertMatches(raw, node, rhs, true);
            case ">" -> assertNumeric(raw, node, rhs, ">");
            case ">=" -> assertNumeric(raw, node, rhs, ">=");
            case "<" -> assertNumeric(raw, node, rhs, "<");
            case "<=" -> assertNumeric(raw, node, rhs, "<=");
            case "type ==" -> assertType(raw, node, rhs);
            default -> new AssertionResult(raw, false, "unknown operator: " + op);
        };
    }

    private static AssertionResult assertEqual(String raw, JsonNode node, String rhs) {
        if (node == null) return result(raw, false, "field missing");
        String actual = node.isTextual() ? node.asText() : node.toString();
        String expected = stripQuotes(rhs);
        try {
            double a = Double.parseDouble(actual);
            double e = Double.parseDouble(expected);
            return result(raw, Math.abs(a - e) < 0.0001, "expected " + expected + ", got " + actual);
        } catch (NumberFormatException ignored) {
            // not numeric
        }
        if (expected.equalsIgnoreCase("true") || expected.equalsIgnoreCase("false")) {
            return result(raw, actual.equalsIgnoreCase(expected), "expected " + expected + ", got " + actual);
        }
        return result(raw, actual.equals(expected), "expected \"" + expected + "\", got \"" + actual + "\"");
    }

    private static AssertionResult assertNotEqual(String raw, JsonNode node, String rhs) {
        if (node == null) return result(raw, true, null);
        String actual = node.isTextual() ? node.asText() : node.toString();
        return result(raw, !actual.equals(stripQuotes(rhs)), "expected value to differ from \"" + stripQuotes(rhs) + "\"");
    }

    private static AssertionResult assertContains(String raw, JsonNode node, String rhs, boolean negate) {
        if (node == null) return result(raw, negate, "field missing");
        String actual = node.asText();
        String sub = stripQuotes(rhs);
        boolean contains = actual.contains(sub);
        if (negate) return result(raw, !contains, "expected not to contain \"" + sub + "\", but it did");
        return result(raw, contains, "expected to contain \"" + sub + "\", got \"" + actual + "\"");
    }

    private static AssertionResult assertMatches(String raw, JsonNode node, String rhs, boolean negate) {
        if (node == null) return result(raw, negate, "field missing");
        String actual = node.isTextual() ? node.asText() : node.toString();
        String regex = stripSlashes(stripQuotes(rhs));
        try {
            boolean found = Pattern.compile(regex).matcher(actual).find();
            if (negate) return result(raw, !found, "expected not to match /" + regex + "/, got \"" + actual + "\"");
            return result(raw, found, "expected to match /" + regex + "/, got \"" + actual + "\"");
        } catch (PatternSyntaxException e) {
            return new AssertionResult(raw, false, "invalid regex: " + e.getDescription());
        }
    }

    private static AssertionResult assertNumeric(String raw, JsonNode node, String rhs, String op) {
        if (node == null) return result(raw, false, "field missing");
        try {
            double actual = node.asDouble();
            double expected = Double.parseDouble(stripQuotes(rhs));
            boolean pass = switch (op) {
                case ">" -> actual > expected;
                case ">=" -> actual >= expected;
                case "<" -> actual < expected;
                case "<=" -> actual <= expected;
                default -> false;
            };
            return result(raw, pass, actual + " " + op + " " + expected + " is false");
        } catch (NumberFormatException e) {
            return new AssertionResult(raw, false, "cannot parse number: " + rhs);
        }
    }

    private static AssertionResult assertType(String raw, JsonNode node, String rhs) {
        if (node == null) return result(raw, false, "field missing");
        String expectedType = stripQuotes(rhs).toLowerCase(Locale.ROOT);
        String actualType = switch (node.getNodeType()) {
            case STRING -> "string";
            case NUMBER -> "number";
            case BOOLEAN -> "boolean";
            case ARRAY -> "array";
            case OBJECT -> "object";
            case NULL -> "null";
            default -> "unknown";
        };
        return result(raw, actualType.equals(expectedType), "expected type " + expectedType + ", got " + actualType);
    }

    // ---------------------------------------------------------------- path resolution (same rules as the 1.x DSL)

    static JsonNode resolvePath(JsonNode root, String path) {
        if (path == null || path.isBlank()) return root;
        String[] segments = path.split("\\.");
        JsonNode current = root;
        for (String seg : segments) {
            if (current == null) return null;
            if (seg.contains("[") && seg.endsWith("]")) {
                int bIdx = seg.indexOf('[');
                String field = seg.substring(0, bIdx);
                String idxStr = seg.substring(bIdx + 1, seg.length() - 1);
                if (!field.isEmpty()) current = current.get(field);
                try {
                    int idx = Integer.parseInt(idxStr);
                    if (current != null && current.isArray()) {
                        current = current.get(idx < 0 ? current.size() + idx : idx);
                    }
                } catch (NumberFormatException ignored) {
                    current = null;
                }
            } else if (seg.equals("length") && (current.isArray() || current.isTextual())) {
                return MAPPER.getNodeFactory().numberNode(current.isArray() ? current.size() : current.asText().length());
            } else {
                current = current.get(seg);
            }
        }
        return current;
    }

    // ---------------------------------------------------------------- helpers

    private static AssertionResult result(String raw, boolean pass, String failReason) {
        return new AssertionResult(raw, pass, pass ? null : failReason);
    }

    private static String stripQuotes(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    private static String stripSlashes(String s) {
        if (s.length() >= 2 && s.startsWith("/") && s.endsWith("/")) return s.substring(1, s.length() - 1);
        return s;
    }

    private static String joinFrom(String[] parts, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(parts[i]);
        }
        return sb.toString();
    }
}
