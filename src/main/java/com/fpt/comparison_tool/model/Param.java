package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

public class Param {

    @JacksonXmlProperty(isAttribute = true)
    private String key;

    @JacksonXmlProperty(isAttribute = true)
    private String value;

    public Param() {}

    public Param(String key, String value) {
        this.key = key;
        this.value = value;
    }

    /** Parse a single "key=value" string into a Param */
    public static Param of(String keyValue) {
        int idx = keyValue.indexOf('=');
        if (idx < 0) return new Param(keyValue.trim(), "");
        return new Param(keyValue.substring(0, idx).trim(), keyValue.substring(idx + 1).trim());
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}