package com.fpt.comparison_tool.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.comparison_tool.model.Body;
import com.fpt.comparison_tool.model.KeyValue;
import com.fpt.comparison_tool.model.Step;
import com.fpt.comparison_tool.suite.Json;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** Reads a cURL command into a step, and writes a resolved request as a cURL command. */
public final class Curl {

    private Curl() {
    }

    public static Step parse(String command) {
        List<String> t = tokens(command.replace("\\\r\n", " ").replace("\\\n", " "));
        if (!t.isEmpty() && t.get(0).equalsIgnoreCase("curl")) t.remove(0);
        String method = null;
        String url = null;
        List<KeyValue> headers = new ArrayList<>();
        List<String> data = new ArrayList<>();
        boolean urlencoded = false;
        boolean get = false;
        for (int i = 0; i < t.size(); i++) {
            String a = t.get(i);
            String next = i + 1 < t.size() ? t.get(i + 1) : null;
            switch (a) {
                case "-X", "--request" -> {
                    method = next;
                    i++;
                }
                case "-H", "--header" -> {
                    if (next != null) {
                        int idx = next.indexOf(':');
                        if (idx > 0) headers.add(KeyValue.of(next.substring(0, idx).trim(), next.substring(idx + 1).trim()));
                    }
                    i++;
                }
                case "-d", "--data", "--data-raw", "--data-binary", "--data-ascii" -> {
                    data.add(next);
                    i++;
                }
                case "--data-urlencode" -> {
                    data.add(next);
                    urlencoded = true;
                    i++;
                }
                case "-u", "--user" -> {
                    if (next != null) {
                        headers.add(KeyValue.of("Authorization", "Basic "
                                + Base64.getEncoder().encodeToString(next.getBytes(StandardCharsets.UTF_8))));
                    }
                    i++;
                }
                case "--url" -> {
                    url = next;
                    i++;
                }
                case "-G", "--get" -> get = true;
                case "-b", "--cookie" -> {
                    if (next != null) headers.add(KeyValue.of("Cookie", next));
                    i++;
                }
                case "-A", "--user-agent" -> {
                    if (next != null) headers.add(KeyValue.of("User-Agent", next));
                    i++;
                }
                case "-e", "--referer", "-o", "--output", "--connect-timeout", "-m", "--max-time" -> i++;
                default -> {
                    if (!a.startsWith("-") && url == null) url = a;
                }
            }
        }
        if (url == null) throw new IllegalArgumentException("No URL found in the cURL command");
        List<KeyValue> query = new ArrayList<>();
        int q = url.indexOf('?');
        if (q >= 0) {
            for (String pair : url.substring(q + 1).split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                query.add(KeyValue.of(decode(eq < 0 ? pair : pair.substring(0, eq)), eq < 0 ? "" : decode(pair.substring(eq + 1))));
            }
            url = url.substring(0, q);
        }
        Body body = null;
        String joined = data.isEmpty() ? null : String.join("&", data);
        String contentType = headers.stream().filter(h -> h.key().equalsIgnoreCase("Content-Type")).map(KeyValue::value)
                .findFirst().orElse("").toLowerCase(Locale.ROOT);
        if (joined != null && get) {
            for (String pair : joined.split("&")) {
                int eq = pair.indexOf('=');
                query.add(KeyValue.of(eq < 0 ? pair : pair.substring(0, eq), eq < 0 ? "" : pair.substring(eq + 1)));
            }
        } else if (joined != null) {
            if (urlencoded || contentType.contains("x-www-form-urlencoded")) {
                List<KeyValue> form = new ArrayList<>();
                for (String pair : joined.split("&")) {
                    int eq = pair.indexOf('=');
                    form.add(KeyValue.of(decode(eq < 0 ? pair : pair.substring(0, eq)), eq < 0 ? "" : decode(pair.substring(eq + 1))));
                }
                body = new Body(Body.FORM, null, null, form);
                headers.removeIf(h -> h.key().equalsIgnoreCase("Content-Type"));
            } else {
                JsonNode node = null;
                try {
                    node = Json.MAPPER.readTree(joined);
                } catch (Exception ignored) {
                    // not JSON
                }
                body = node != null && (node.isObject() || node.isArray()) ? new Body(Body.JSON, node, null, null)
                        : new Body(contentType.contains("json") ? Body.JSON : Body.TEXT, null, joined, null);
            }
        }
        if (method == null) method = body != null ? "POST" : "GET";
        String name = method + " " + url.replaceFirst("^https?://[^/]+", "");
        return new Step(name, null, method, url, query, headers, body, null, null, null, null, null);
    }

    public static String build(String method, String url, List<KeyValue> headers, String body) {
        StringBuilder sb = new StringBuilder("curl -k -X ").append(method).append(" '").append(q(url)).append('\'');
        for (KeyValue h : headers) sb.append(" \\\n  -H '").append(q(h.key() + ": " + h.value())).append('\'');
        if (body != null && !body.isEmpty()) sb.append(" \\\n  --data-raw '").append(q(body)).append('\'');
        return sb.toString();
    }

    private static String q(String s) {
        return s.replace("'", "'\\''");
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    /** Splits a command line like a POSIX shell: quotes, escapes, $'...' strings. */
    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inToken = false;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                if (inToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
                i++;
            } else if (c == '\'' ) {
                inToken = true;
                int end = s.indexOf('\'', i + 1);
                if (end < 0) end = s.length();
                cur.append(s, i + 1, end);
                i = end + 1;
            } else if (c == '$' && i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                inToken = true;
                i += 2;
                while (i < s.length() && s.charAt(i) != '\'') {
                    char d = s.charAt(i);
                    if (d == '\\' && i + 1 < s.length()) {
                        char e = s.charAt(i + 1);
                        cur.append(switch (e) {
                            case 'n' -> '\n';
                            case 't' -> '\t';
                            case 'r' -> '\r';
                            default -> e;
                        });
                        i += 2;
                    } else {
                        cur.append(d);
                        i++;
                    }
                }
                i++;
            } else if (c == '"') {
                inToken = true;
                i++;
                while (i < s.length() && s.charAt(i) != '"') {
                    char d = s.charAt(i);
                    if (d == '\\' && i + 1 < s.length() && "\"\\$`".indexOf(s.charAt(i + 1)) >= 0) {
                        cur.append(s.charAt(i + 1));
                        i += 2;
                    } else {
                        cur.append(d);
                        i++;
                    }
                }
                i++;
            } else if (c == '\\' && i + 1 < s.length()) {
                inToken = true;
                cur.append(s.charAt(i + 1));
                i += 2;
            } else if (c == '^' && i + 1 < s.length() && (s.charAt(i + 1) == '\n' || s.charAt(i + 1) == '\r')) {
                i++;
            } else {
                inToken = true;
                cur.append(c);
                i++;
            }
        }
        if (inToken) out.add(cur.toString());
        return out;
    }
}
