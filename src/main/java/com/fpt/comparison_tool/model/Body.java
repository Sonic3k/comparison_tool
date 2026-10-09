package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;

/**
 * Request body. type json keeps a parsed document in {@code json} (readable in the suite file) or, when the text is
 * not valid JSON on its own (for example an unquoted {{number}} placeholder), the text in {@code raw}.
 * type form sends application/x-www-form-urlencoded fields; type text sends {@code raw} as is.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"type", "json", "raw", "form"})
public record Body(String type, @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode json, String raw, List<KeyValue> form) {

    public static final String JSON = "json";
    public static final String FORM = "form";
    public static final String TEXT = "text";

    public Body {
        type = type == null ? JSON : type.trim().toLowerCase(Locale.ROOT);
        if (json != null && (json.isMissingNode() || json.isNull())) json = null;
        form = Lists.of(form);
    }

    public boolean empty() {
        return switch (type) {
            case FORM -> form.stream().noneMatch(KeyValue::active);
            case JSON -> json == null && (raw == null || raw.isBlank());
            default -> raw == null || raw.isEmpty();
        };
    }
}
