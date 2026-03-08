package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

public class DefaultHeader {

    @JacksonXmlProperty(isAttribute = true, localName = "name")
    private String name;

    @JacksonXmlProperty(isAttribute = true, localName = "source")
    private String sourceValue;

    @JacksonXmlProperty(isAttribute = true, localName = "target")
    private String targetValue;

    public DefaultHeader() {}

    public DefaultHeader(String name, String sourceValue, String targetValue) {
        this.name = name;
        this.sourceValue = sourceValue;
        this.targetValue = targetValue;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSourceValue() { return sourceValue; }
    public void setSourceValue(String sourceValue) { this.sourceValue = sourceValue; }

    public String getTargetValue() { return targetValue; }
    public void setTargetValue(String targetValue) { this.targetValue = targetValue; }
}