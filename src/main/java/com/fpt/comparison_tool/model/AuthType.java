package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How a request is authenticated.
 *
 * CLIENT_CREDENTIALS is the generic "fetch a token from an OAuth2 token
 * endpoint" type. The actual OAuth2 grant is chosen per profile via
 * {@link AuthProfile#getGrantType()} - client_credentials, password or
 * refresh_token. The enum constant keeps its historical name so existing
 * suites, Excel workbooks and XML exports continue to load unchanged.
 *
 * Aliases are accepted on input so a suite can be written with the clearer
 * type "oauth2", and so a profile mistakenly typed as "password" resolves to
 * the OAuth2 handler instead of silently degrading to NONE.
 */
public enum AuthType {
    NONE, BASIC, BEARER, CLIENT_CREDENTIALS, SAML;

    @JsonValue
    public String toValue() { return name().toLowerCase(); }

    /** True when this type obtains its token from an OAuth2 token endpoint. */
    public boolean isOAuth2() { return this == CLIENT_CREDENTIALS; }

    @JsonCreator
    public static AuthType from(String v) {
        if (v == null || v.isBlank()) return NONE;

        String norm = v.trim().toLowerCase().replace('-', '_').replace(" ", "");

        for (AuthType t : values()) {
            if (t.name().equalsIgnoreCase(norm) || t.toValue().equalsIgnoreCase(norm)) return t;
        }

        // Aliases that all mean "OAuth2 token endpoint"; the grant itself is a
        // separate field on the profile.
        return switch (norm) {
            case "oauth", "oauth2", "oauth_2", "clientcredentials",
                 "client_credential", "password", "refresh_token", "refreshtoken" -> CLIENT_CREDENTIALS;
            default -> NONE;
        };
    }
}
