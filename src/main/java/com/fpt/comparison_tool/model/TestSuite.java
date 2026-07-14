package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import java.util.ArrayList;
import java.util.List;

@JacksonXmlRootElement(localName = "testSuite")
public class TestSuite {

    private SuiteSettings settings;

    @JacksonXmlElementWrapper(localName = "environments")
    @JacksonXmlProperty(localName = "environment")
    @JsonProperty("environments")              // ← force JSON key to match field name
    private List<Environment> environments;

    @JacksonXmlElementWrapper(localName = "authProfiles")
    @JacksonXmlProperty(localName = "authProfile")
    @JsonProperty("authProfiles")
    private List<AuthProfile> authProfiles;

    @JacksonXmlElementWrapper(localName = "globalVariables")
    @JacksonXmlProperty(localName = "variable")
    @JsonProperty("globalVariables")
    private List<GlobalVariable> globalVariables;

    @JacksonXmlElementWrapper(localName = "testGroups")
    @JacksonXmlProperty(localName = "testGroup")
    @JsonProperty("testGroups")
    private List<TestGroup> testGroups;

    public TestSuite() {
        this.environments    = new ArrayList<>();
        this.authProfiles    = new ArrayList<>();
        this.testGroups      = new ArrayList<>();
        this.globalVariables = new ArrayList<>();
    }

    public TestSuite(SuiteSettings settings, List<Environment> environments,
                     List<AuthProfile> authProfiles, List<TestGroup> testGroups) {
        this.settings     = settings;
        this.environments = environments != null ? environments : new ArrayList<>();
        this.authProfiles = authProfiles != null ? authProfiles : new ArrayList<>();
        this.testGroups   = testGroups   != null ? testGroups   : new ArrayList<>();
        this.globalVariables = new ArrayList<>();
    }

    public List<GlobalVariable> getGlobalVariables() {
        if (globalVariables == null) globalVariables = new ArrayList<>();
        return globalVariables;
    }
    public void setGlobalVariables(List<GlobalVariable> v) { this.globalVariables = v; }

    /** Upsert by name — parallel flows write concurrently; last write wins. */
    public synchronized void putGlobalVariable(String name, String value, String updatedAt) {
        if (name == null || name.isBlank()) return;
        for (GlobalVariable v : getGlobalVariables()) {
            if (name.equals(v.getName())) { v.setValue(value); v.setUpdatedAt(updatedAt); return; }
        }
        getGlobalVariables().add(new GlobalVariable(name, value, updatedAt));
    }

    public void addTestGroup(TestGroup group)   { this.testGroups.add(group); }
    public void addAuthProfile(AuthProfile p)   { this.authProfiles.add(p); }
    public void addEnvironment(Environment env) { this.environments.add(env); }

    /** Enforce Test Case invariants across all groups. See TestGroup#normalize(). */
    public void normalize() {
        if (testGroups == null) return;
        for (TestGroup g : testGroups) g.normalize();
    }

    public Environment findEnvironment(String name) {
        if (name == null || environments == null) return null;
        return environments.stream()
                .filter(e -> name.equals(e.getName()))
                .findFirst().orElse(null);
    }

    public SuiteSettings getSettings()               { return settings; }
    public void setSettings(SuiteSettings s)         { this.settings = s; }

    public List<Environment> getEnvironments()       { return environments; }
    public void setEnvironments(List<Environment> e) { this.environments = e; }

    public List<AuthProfile> getAuthProfiles()       { return authProfiles; }
    public void setAuthProfiles(List<AuthProfile> l) { this.authProfiles = l; }

    public List<TestGroup> getTestGroups()           { return testGroups; }
    public void setTestGroups(List<TestGroup> l)     { this.testGroups = l; }
}