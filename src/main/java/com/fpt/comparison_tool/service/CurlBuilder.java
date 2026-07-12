package com.fpt.comparison_tool.service;

import com.fpt.comparison_tool.model.*;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

/**
 * Builds a copy-pastable cURL command for a TestRequest against a given Environment.
 *
 * The cURL reflects what the tool actually sends: base URL + endpoint + query,
 * default env headers, TC header overrides, auth header (with token already
 * fetched if OAuth2), and the request body (form or JSON).
 *
 * Variable placeholders like {{varName}} are NOT resolved — the cURL is meant
 * for manual debugging, so any unresolved placeholders stay visible.
 */
@Service
public class CurlBuilder {

    private final AuthService authService;

    public CurlBuilder(AuthService authService) {
        this.authService = authService;
    }

    public String build(Environment env, TestRequest tc, AuthProfile auth) {
        if (env == null) return "# Environment not configured";

        HttpHeaders headers = new HttpHeaders();

        // 1. Default env headers
        if (env.getHeaders() != null) {
            for (Param h : env.getHeaders()) {
                headers.set(h.getKey(), h.getValue());
            }
        }

        // 2. TC header overrides
        if (tc.getHeaders() != null && !tc.getHeaders().isBlank()) {
            for (String line : tc.getHeaders().split("\\r?\\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int idx = line.indexOf(':');
                if (idx > 0) headers.set(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }
        }

        // 3. Auth (fetches OAuth2 token if applicable)
        try {
            authService.applyAuth(auth, headers);
        } catch (Exception e) {
            headers.set("X-Auth-Note", "auth resolution failed: " + e.getMessage());
        }

        // 4. URL
        String url = (env.getUrl() != null ? env.getUrl() : "") + tc.getEndpoint();
        if (tc.getQueryParams() != null && !tc.getQueryParams().isEmpty()) {
            url += "?" + tc.getQueryParamsAsString();
        }

        // 5. Body
        String bodyArg = "";
        boolean hasForm = tc.getFormParams() != null && !tc.getFormParams().isEmpty();
        boolean hasJson = tc.getJsonBody() != null && !tc.getJsonBody().isBlank();

        if (hasForm) {
            // form-urlencoded — force content type, build --data-urlencode per pair
            headers.set(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded");
            StringBuilder b = new StringBuilder();
            for (Param p : tc.getFormParams()) {
                b.append(" \\\n  --data-urlencode ").append(shellQuote(p.getKey() + "=" + p.getValue()));
            }
            bodyArg = b.toString();
        } else if (hasJson) {
            if (!headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
                headers.set(HttpHeaders.CONTENT_TYPE, "application/json");
            }
            bodyArg = " \\\n  --data " + shellQuote(tc.getJsonBody());
        }

        // 6. Assemble
        StringBuilder curl = new StringBuilder();
        curl.append("curl -X ").append(tc.getMethod().name()).append(" \\\n  ").append(shellQuote(url));
        for (var entry : headers.entrySet()) {
            for (String v : entry.getValue()) {
                curl.append(" \\\n  -H ").append(shellQuote(entry.getKey() + ": " + v));
            }
        }
        curl.append(bodyArg);
        return curl.toString();
    }

    /** Wrap in single quotes, escape embedded single quotes (POSIX-safe). */
    private String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
