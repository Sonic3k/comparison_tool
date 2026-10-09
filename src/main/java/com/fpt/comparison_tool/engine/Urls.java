package com.fpt.comparison_tool.engine;

import com.fpt.comparison_tool.model.KeyValue;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** URL assembly: base URL joining, query parameters and lenient encoding of characters a URI cannot hold. */
public final class Urls {

    private Urls() {
    }

    public static boolean isAbsolute(String url) {
        String u = url == null ? "" : url.trim().toLowerCase(java.util.Locale.ROOT);
        return u.startsWith("http://") || u.startsWith("https://");
    }

    public static String join(String base, String path) {
        if (isAbsolute(path) || base == null || base.isBlank()) return path;
        if (path == null || path.isEmpty()) return base;
        boolean baseSlash = base.endsWith("/");
        boolean pathSlash = path.startsWith("/");
        if (baseSlash && pathSlash) return base + path.substring(1);
        if (!baseSlash && !pathSlash && !path.startsWith("?")) return base + "/" + path;
        return base + path;
    }

    public static String withQuery(String url, List<KeyValue> params) {
        StringBuilder sb = new StringBuilder(url);
        boolean first = !url.contains("?");
        for (KeyValue p : params) {
            sb.append(first ? '?' : '&');
            first = false;
            sb.append(encode(p.key())).append('=').append(encode(p.value()));
        }
        return sb.toString();
    }

    public static String encode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static String form(List<KeyValue> fields) {
        StringBuilder sb = new StringBuilder();
        for (KeyValue f : fields) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(URLEncoder.encode(f.key(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(f.value(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** Parses a URL, percent-encoding spaces, quotes, braces and non-ASCII characters that a raw URI rejects. */
    public static URI toUri(String url) {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            StringBuilder sb = new StringBuilder();
            for (char c : url.toCharArray()) {
                if (c > 127 || " \"<>\\^`{|}".indexOf(c) >= 0) {
                    for (byte b : String.valueOf(c).getBytes(StandardCharsets.UTF_8)) {
                        sb.append('%').append(String.format("%02X", b));
                    }
                } else {
                    sb.append(c);
                }
            }
            return URI.create(sb.toString());
        }
    }
}
