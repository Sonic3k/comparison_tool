package com.fpt.comparison_tool.result;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fpt.comparison_tool.model.KeyValue;

import java.util.List;

/** What was sent to one environment and what came back. Bodies may be dropped or truncated by the capture setting. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"env", "method", "url", "requestHeaders", "requestBody", "status", "timeMs", "responseHeaders",
        "contentType", "responseSize", "responseBody", "truncated", "error", "vars"})
public record Exchange(String env, String method, String url, List<KeyValue> requestHeaders, String requestBody,
                       Integer status, Long timeMs, List<KeyValue> responseHeaders, String contentType,
                       Long responseSize, String responseBody, Boolean truncated, String error, List<VarUse> vars) {

    /** Longest variable value kept when bodies are dropped; the values still say what the step used. */
    public static final int VALUE_LIMIT_WITHOUT_BODIES = 2048;

    public Exchange(String env, String method, String url, List<KeyValue> requestHeaders, String requestBody,
                    Integer status, Long timeMs, List<KeyValue> responseHeaders, String contentType,
                    Long responseSize, String responseBody, Boolean truncated, String error) {
        this(env, method, url, requestHeaders, requestBody, status, timeMs, responseHeaders, contentType, responseSize,
                responseBody, truncated, error, null);
    }

    public Exchange withVars(List<VarUse> newVars) {
        return new Exchange(env, method, url, requestHeaders, requestBody, status, timeMs, responseHeaders, contentType,
                responseSize, responseBody, truncated, error, newVars == null || newVars.isEmpty() ? null : List.copyOf(newVars));
    }

    public Exchange withoutBodies() {
        return new Exchange(env, method, url, null, null, status, timeMs, null, contentType, responseSize, null,
                null, error, cut(vars, VALUE_LIMIT_WITHOUT_BODIES));
    }

    public Exchange truncate(int limitChars) {
        boolean cutReq = requestBody != null && requestBody.length() > limitChars;
        boolean cutRes = responseBody != null && responseBody.length() > limitChars;
        List<VarUse> v = cut(vars, limitChars);
        if (!cutReq && !cutRes && v == vars) return this;
        return new Exchange(env, method, url, requestHeaders,
                cutReq ? requestBody.substring(0, limitChars) : requestBody, status, timeMs, responseHeaders,
                contentType, responseSize, cutRes ? responseBody.substring(0, limitChars) : responseBody,
                cutReq || cutRes ? Boolean.TRUE : truncated, error, v);
    }

    private static List<VarUse> cut(List<VarUse> vars, int limit) {
        if (vars == null || vars.stream().noneMatch(u -> u.value() != null && u.value().length() > limit)) return vars;
        return vars.stream().map(u -> u.truncated(limit)).toList();
    }
}
