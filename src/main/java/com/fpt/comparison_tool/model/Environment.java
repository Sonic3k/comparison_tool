package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

import java.util.ArrayList;
import java.util.List;

public class Environment {

    @JacksonXmlProperty(isAttribute = true)
    private String name;

    @JacksonXmlProperty(isAttribute = true)
    private String url;

    @JacksonXmlProperty(isAttribute = true)
    private String authProfile;

    @JacksonXmlElementWrapper(localName = "headers")
    @JacksonXmlProperty(localName = "param")
    private List<Param> headers;

    public Environment() {
        this.headers = new ArrayList<>();
    }

    public Environment(String name, String url, String authProfile, List<Param> headers) {
        this.name        = name;
        this.url         = url;
        this.authProfile = authProfile;
        this.headers     = headers != null ? headers : new ArrayList<>();
    }

    public String getName()                  { return name; }
    public void setName(String name)         { this.name = name; }

    public String getUrl()                   { return url; }
    public void setUrl(String url)           { this.url = url; }

    public String getAuthProfile()           { return authProfile; }
    public void setAuthProfile(String ap)    { this.authProfile = ap; }

    public List<Param> getHeaders()          { return headers; }
    public void setHeaders(List<Param> h)    { this.headers = h; }
}