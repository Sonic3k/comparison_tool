package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Locale;

/**
 * One HTTP call of a test case, setup or teardown. url is absolute, starts with a {{variable}} that resolves to an
 * absolute URL, or is a path appended to the environment base URL. auth: empty = inherit, "none", or a profile name.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"name", "disabled", "method", "url", "query", "headers", "body", "auth", "extract", "expect",
        "options", "notes"})
public record Step(String name, Boolean disabled, String method, String url, List<KeyValue> query,
                   List<KeyValue> headers, Body body, String auth, List<Extract> extract, Expect expect,
                   StepOptions options, String notes) {

    public Step {
        name = name == null ? "" : name;
        disabled = Lists.flag(disabled);
        method = method == null || method.isBlank() ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        url = url == null ? "" : url.trim();
        query = Lists.of(query);
        headers = Lists.of(headers);
        if (body != null && body.empty()) body = null;
        auth = Lists.text(auth);
        extract = extract == null ? List.of()
                : extract.stream().filter(e -> e != null && !e.name().isEmpty() && !e.path().isEmpty()).toList();
        if (expect != null && expect.blank()) expect = null;
        if (options != null && options.equals(StepOptions.none())) options = null;
        notes = Lists.text(notes);
    }

    public boolean active() {
        return disabled == null;
    }

    public Expect expectOrEmpty() {
        return expect == null ? new Expect(null, null, null) : expect;
    }

    public StepOptions optionsOrNone() {
        return options == null ? StepOptions.none() : options;
    }
}
