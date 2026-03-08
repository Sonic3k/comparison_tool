package com.fpt.comparison_tool.controller;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Enumeration;
import java.util.List;

/**
 * Transparent HTTP proxy — frontend sends requests here, backend forwards them.
 *
 * Usage: POST /api/proxy
 * Body: {
 *   "method":  "GET",
 *   "url":     "https://api-legacy.company.com/api/users/123",
 *   "headers": { "X-Custom": "value" },
 *   "body":    "..."
 * }
 */
@RestController
@RequestMapping("/api/proxy")
public class ProxyController {

    private final RestTemplate restTemplate;

    public ProxyController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @PostMapping
    public ResponseEntity<String> proxy(@RequestBody ProxyRequest req) {
        try {
            HttpHeaders headers = new HttpHeaders();
            if (req.getHeaders() != null) {
                req.getHeaders().forEach(headers::set);
            }

            HttpEntity<String> entity = new HttpEntity<>(req.getBody(), headers);
            HttpMethod method = HttpMethod.valueOf(req.getMethod().toUpperCase());

            return restTemplate.exchange(URI.create(req.getUrl()), method, entity, String.class);

        } catch (HttpStatusCodeException e) {
            // Return the actual error response from target — don't swallow it
            return ResponseEntity.status(e.getStatusCode())
                    .headers(e.getResponseHeaders())
                    .body(e.getResponseBodyAsString());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"error\": \"Proxy error: " + e.getMessage() + "\"}");
        }
    }

    // ─── Request DTO ──────────────────────────────────────────────────────────

    public static class ProxyRequest {
        private String method;
        private String url;
        private java.util.Map<String, String> headers;
        private String body;

        public String getMethod()  { return method; }
        public void setMethod(String method) { this.method = method; }

        public String getUrl()     { return url; }
        public void setUrl(String url) { this.url = url; }

        public java.util.Map<String, String> getHeaders() { return headers; }
        public void setHeaders(java.util.Map<String, String> headers) { this.headers = headers; }

        public String getBody()    { return body; }
        public void setBody(String body) { this.body = body; }
    }
}