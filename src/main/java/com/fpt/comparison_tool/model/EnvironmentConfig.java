package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

import java.util.ArrayList;
import java.util.List;

public class EnvironmentConfig {

    private EnvironmentEndpoint source;
    private EnvironmentEndpoint target;

    @JacksonXmlElementWrapper(localName = "defaultHeaders")
    @JacksonXmlProperty(localName = "defaultHeader")
    private List<DefaultHeader> defaultHeaders;

    public EnvironmentConfig() {
        this.defaultHeaders = new ArrayList<>();
    }

    public EnvironmentConfig(String sourceUrl, String sourceAuthProfile,
                             String targetUrl, String targetAuthProfile,
                             List<DefaultHeader> defaultHeaders) {
        this.source = new EnvironmentEndpoint(sourceUrl, sourceAuthProfile);
        this.target = new EnvironmentEndpoint(targetUrl, targetAuthProfile);
        this.defaultHeaders = defaultHeaders != null ? defaultHeaders : new ArrayList<>();
    }

    // ─── Convenience accessors (used by ExcelGenerator) ──────────────────────

    public String getSourceUrl()         { return source != null ? source.getUrl()         : null; }
    public String getSourceAuthProfile() { return source != null ? source.getAuthProfile() : null; }
    public String getTargetUrl()         { return target != null ? target.getUrl()         : null; }
    public String getTargetAuthProfile() { return target != null ? target.getAuthProfile() : null; }

    public EnvironmentEndpoint getSource() { return source; }
    public void setSource(EnvironmentEndpoint source) { this.source = source; }

    public EnvironmentEndpoint getTarget() { return target; }
    public void setTarget(EnvironmentEndpoint target) { this.target = target; }

    public List<DefaultHeader> getDefaultHeaders() { return defaultHeaders; }
    public void setDefaultHeaders(List<DefaultHeader> defaultHeaders) { this.defaultHeaders = defaultHeaders; }

    // ─── Nested class — serializes as <source url="..." authProfile="..."/> ──

    public static class EnvironmentEndpoint {

        @JacksonXmlProperty(isAttribute = true)
        private String url;

        @JacksonXmlProperty(isAttribute = true)
        private String authProfile;

        public EnvironmentEndpoint() {}

        public EnvironmentEndpoint(String url, String authProfile) {
            this.url = url;
            this.authProfile = authProfile;
        }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getAuthProfile() { return authProfile; }
        public void setAuthProfile(String authProfile) { this.authProfile = authProfile; }
    }
}