package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import java.util.ArrayList;
import java.util.List;

@JacksonXmlRootElement(localName = "testSuite")
public class TestSuite {

    private String id; // assigned by SuiteRegistry on import
    private SuiteSettings settings;

    @JacksonXmlElementWrapper(localName = "environments")
    @JacksonXmlProperty(localName = "environment")
    @JsonProperty("environments")              // ← force JSON key to match field name
    private List<Environment> environments;

    @JacksonXmlElementWrapper(localName = "authProfiles")
    @JacksonXmlProperty(localName = "authProfile")
    @JsonProperty("authProfiles")
    private List<AuthProfile> authProfiles;

    @JacksonXmlElementWrapper(localName = "testGroups")
    @JacksonXmlProperty(localName = "testGroup")
    @JsonProperty("testGroups")
    private List<TestGroup> testGroups;

    public TestSuite() {
        this.environments = new ArrayList<>();
        this.authProfiles = new ArrayList<>();
        this.testGroups   = new ArrayList<>();
    }

    public TestSuite(SuiteSettings settings, List<Environment> environments,
                     List<AuthProfile> authProfiles, List<TestGroup> testGroups) {
        this.settings     = settings;
        this.environments = environments != null ? environments : new ArrayList<>();
        this.authProfiles = authProfiles != null ? authProfiles : new ArrayList<>();
        this.testGroups   = testGroups   != null ? testGroups   : new ArrayList<>();
    }

    public void addTestGroup(TestGroup group)   { this.testGroups.add(group); }
    public void addAuthProfile(AuthProfile p)   { this.authProfiles.add(p); }
    public void addEnvironment(Environment env) { this.environments.add(env); }

    public Environment findEnvironment(String name) {
        if (name == null || environments == null) return null;
        return environments.stream()
                .filter(e -> name.equals(e.getName()))
                .findFirst().orElse(null);
    }

    public String getId()                             { return id; }
    public void setId(String id)                      { this.id = id; }
    public SuiteSettings getSettings()               { return settings; }
    public void setSettings(SuiteSettings s)         { this.settings = s; }

    public List<Environment> getEnvironments()       { return environments; }
    public void setEnvironments(List<Environment> e) { this.environments = e; }

    public List<AuthProfile> getAuthProfiles()       { return authProfiles; }
    public void setAuthProfiles(List<AuthProfile> l) { this.authProfiles = l; }

    public List<TestGroup> getTestGroups()           { return testGroups; }
    public void setTestGroups(List<TestGroup> l)     { this.testGroups = l; }
}