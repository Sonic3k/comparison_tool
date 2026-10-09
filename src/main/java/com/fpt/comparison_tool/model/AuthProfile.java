package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Locale;

/**
 * A named way of authenticating requests: none, basic, bearer or oauth2 (grant client_credentials, password or
 * refresh_token). Secret fields may use ${ENV_VAR} (machine environment) or {{variable}} (run variables).
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"name", "type", "grantType", "tokenUrl", "clientId", "clientSecret", "username", "password",
        "scope", "token", "refreshToken", "extraParams", "description"})
public record AuthProfile(String name, String type, String grantType, String tokenUrl, String clientId,
                          String clientSecret, String username, String password, String scope, String token,
                          String refreshToken, List<KeyValue> extraParams, String description) {

    public static final String NONE = "none";
    public static final String BASIC = "basic";
    public static final String BEARER = "bearer";
    public static final String OAUTH2 = "oauth2";

    public AuthProfile {
        name = name == null ? "" : name;
        type = normalizeType(type);
        grantType = Lists.text(grantType);
        extraParams = Lists.of(extraParams);
    }

    public static String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) return NONE;
        String t = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(" ", "");
        return switch (t) {
            case "basic" -> BASIC;
            case "bearer", "token" -> BEARER;
            case "oauth", "oauth2", "oauth_2", "client_credentials", "clientcredentials", "client_credential",
                 "password", "refresh_token", "refreshtoken" -> OAUTH2;
            default -> NONE;
        };
    }

    public String resolvedGrant() {
        if (grantType == null) return "client_credentials";
        return grantType.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
