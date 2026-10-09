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
        "contentType", "responseSize", "responseBody", "truncated", "error"})
public record Exchange(String env, String method, String url, List<KeyValue> requestHeaders, String requestBody,
                       Integer status, Long timeMs, List<KeyValue> responseHeaders, String contentType,
                       Long responseSize, String responseBody, Boolean truncated, String error) {

    public Exchange withoutBodies() {
        return new Exchange(env, method, url, null, null, status, timeMs, null, contentType, responseSize, null,
                null, error);
    }

    public Exchange truncate(int limitChars) {
        boolean cutReq = requestBody != null && requestBody.length() > limitChars;
        boolean cutRes = responseBody != null && responseBody.length() > limitChars;
        if (!cutReq && !cutRes) return this;
        return new Exchange(env, method, url, requestHeaders,
                cutReq ? requestBody.substring(0, limitChars) : requestBody, status, timeMs, responseHeaders,
                contentType, responseSize, cutRes ? responseBody.substring(0, limitChars) : responseBody, Boolean.TRUE,
                error);
    }
}
