package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

public class AuthProfile {

    @JacksonXmlProperty(isAttribute = true)
    private String name;

    @JacksonXmlProperty(isAttribute = true)
    private AuthType type;      // XML: type="saml" — field renamed to match XML, drop "auth" prefix

    // Long text — child element, not attribute
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

    @JacksonXmlProperty(isAttribute = true)
    private String additionalConfig;

    public AuthProfile() {}

    public AuthProfile(String name, AuthType type, String description) {
        this.name = name;
        this.type = type;
        this.description = description;
    }

    // ─── Fluent setters ───────────────────────────────────────────────────────

    public AuthProfile withTokenUrl(String tokenUrl)         { this.tokenUrl = tokenUrl;             return this; }
    public AuthProfile withUsername(String username)         { this.username = username;             return this; }
    public AuthProfile withPassword(String password)         { this.password = password;             return this; }
    public AuthProfile withClientId(String clientId)         { this.clientId = clientId;             return this; }
    public AuthProfile withClientSecret(String clientSecret) { this.clientSecret = clientSecret;     return this; }
    public AuthProfile withScope(String scope)               { this.scope = scope;                   return this; }
    public AuthProfile withEntityId(String entityId)         { this.entityId = entityId;             return this; }
    public AuthProfile withToken(String token)               { this.token = token;                   return this; }
    public AuthProfile withAdditionalConfig(String cfg)      { this.additionalConfig = cfg;          return this; }

    // ─── Getters / Setters ────────────────────────────────────────────────────

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

    public String getAdditionalConfig() { return additionalConfig; }
    public void setAdditionalConfig(String additionalConfig) { this.additionalConfig = additionalConfig; }
}