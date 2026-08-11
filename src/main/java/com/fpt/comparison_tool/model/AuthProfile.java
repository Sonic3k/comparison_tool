package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * One named way of authenticating requests.
 *
 * For type=client_credentials (the generic OAuth2 type) the grant is chosen
 * with {@link #getGrantType()}:
 *
 *   client_credentials - machine-to-machine. Needs clientId + clientSecret.
 *                        The token carries no user identity.
 *   password           - Resource Owner Password Credentials. Needs clientId +
 *                        username + password. Produces a user token, so claims
 *                        such as preferred_username are present. Requires the
 *                        identity provider to permit the grant.
 *   refresh_token      - exchanges a stored refresh token for a fresh access
 *                        token. Needs clientId + refreshToken. Also a user
 *                        token, and does not require an interactive browser.
 *
 * extraParams are appended to the token request body last, so they can add or
 * override provider-specific parameters (resource, tenant, audience, ...)
 * without the tool needing to know about them.
 *
 * Any of tokenUrl, username, password, clientId, clientSecret, scope, token and
 * refreshToken may be written as ${ENV_VAR}; see SecretResolver.
 */
public class AuthProfile {

    public static final String GRANT_CLIENT_CREDENTIALS = "client_credentials";
    public static final String GRANT_PASSWORD           = "password";
    public static final String GRANT_REFRESH_TOKEN      = "refresh_token";

    @JacksonXmlProperty(isAttribute = true)
    private String name;

    @JacksonXmlProperty(isAttribute = true)
    private AuthType type;      // XML: type="saml" - field renamed to match XML, drop "auth" prefix

    // Long text - child element, not attribute
    private String description;

    @JacksonXmlProperty(isAttribute = true)
    private String tokenUrl;

    @JacksonXmlProperty(isAttribute = true)
    private String username;

    @JacksonXmlProperty(isAttribute = true)
    private String password;

    @JacksonXmlProperty(isAttribute = true)
    private String clientId;

    @JacksonXmlProperty(isAttribute = true)
    private String clientSecret;

    @JacksonXmlProperty(isAttribute = true)
    private String scope;

    @JacksonXmlProperty(isAttribute = true)
    private String entityId;

    @JacksonXmlProperty(isAttribute = true)
    private String token;

    /** OAuth2 grant used when type is client_credentials. Blank means client_credentials. */
    @JacksonXmlProperty(isAttribute = true)
    private String grantType;

    /** Used when grantType is refresh_token. */
    @JacksonXmlProperty(isAttribute = true)
    private String refreshToken;

    /** Extra form parameters appended to the token request body. */
    @JacksonXmlElementWrapper(localName = "extraParams")
    @JacksonXmlProperty(localName = "param")
    private List<Param> extraParams;

    @JacksonXmlProperty(isAttribute = true)
    private String additionalConfig;

    public AuthProfile() {}

    public AuthProfile(String name, AuthType type, String description) {
        this.name = name;
        this.type = type;
        this.description = description;
    }

    // --- Derived -------------------------------------------------------------

    /**
     * The grant to use, normalised. Absent or blank means client_credentials so
     * that every suite written before this field existed keeps its behaviour.
     */
    public String resolvedGrantType() {
        if (grantType == null || grantType.isBlank()) return GRANT_CLIENT_CREDENTIALS;
        return grantType.trim().toLowerCase().replace('-', '_');
    }

    // --- Fluent setters ------------------------------------------------------

    public AuthProfile withTokenUrl(String tokenUrl)         { this.tokenUrl = tokenUrl;             return this; }
    public AuthProfile withUsername(String username)         { this.username = username;             return this; }
    public AuthProfile withPassword(String password)         { this.password = password;             return this; }
    public AuthProfile withClientId(String clientId)         { this.clientId = clientId;             return this; }
    public AuthProfile withClientSecret(String clientSecret) { this.clientSecret = clientSecret;     return this; }
    public AuthProfile withScope(String scope)               { this.scope = scope;                   return this; }
    public AuthProfile withEntityId(String entityId)         { this.entityId = entityId;             return this; }
    public AuthProfile withToken(String token)               { this.token = token;                   return this; }
    public AuthProfile withGrantType(String grantType)       { this.grantType = grantType;           return this; }
    public AuthProfile withRefreshToken(String refreshToken) { this.refreshToken = refreshToken;     return this; }
    public AuthProfile withAdditionalConfig(String cfg)      { this.additionalConfig = cfg;          return this; }

    public AuthProfile withExtraParams(List<Param> params)   { this.extraParams = params;            return this; }

    public AuthProfile addExtraParam(String key, String value) {
        if (this.extraParams == null) this.extraParams = new ArrayList<>();
        this.extraParams.add(new Param(key, value));
        return this;
    }

    // --- Getters / Setters ---------------------------------------------------

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public AuthType getType()             { return type; }
    public void setType(AuthType type)    { this.type = type; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getTokenUrl() { return tokenUrl; }
    public void setTokenUrl(String tokenUrl) { this.tokenUrl = tokenUrl; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getGrantType() { return grantType; }
    public void setGrantType(String grantType) { this.grantType = grantType; }

    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }

    public List<Param> getExtraParams() { return extraParams; }
    public void setExtraParams(List<Param> extraParams) { this.extraParams = extraParams; }

    public String getAdditionalConfig() { return additionalConfig; }
    public void setAdditionalConfig(String additionalConfig) { this.additionalConfig = additionalConfig; }
}
