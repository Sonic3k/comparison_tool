package com.fpt.comparison_tool.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

import java.util.ArrayList;
import java.util.List;

public class ComparisonConfig {

    // Serialized as a comma-separated string in both Excel and XML
    @JacksonXmlProperty(localName = "ignoreFields")
    private String ignoreFieldsRaw;

    private boolean caseSensitive;
    private boolean ignoreArrayOrder;
    private double numericTolerance;
    /**
     * When false (default): any 5xx response is treated as an execution error —
     * the test is marked ERROR and skipped from comparison.
     * When true: 4xx/5xx responses are captured and compared normally,
     * allowing intentional error behavior to be verified across environments.
     */
    private boolean compareErrorResponses = false;

    public ComparisonConfig() {}

    public ComparisonConfig(List<String> ignoreFields, boolean caseSensitive,
                            boolean ignoreArrayOrder, double numericTolerance) {
        this.ignoreFieldsRaw = ignoreFields != null ? String.join(",", ignoreFields) : "";
        this.caseSensitive = caseSensitive;
        this.ignoreArrayOrder = ignoreArrayOrder;
        this.numericTolerance = numericTolerance;
    }

    @JsonIgnore
    public List<String> getIgnoreFieldsAsList() {
        if (ignoreFieldsRaw == null || ignoreFieldsRaw.isBlank()) return new ArrayList<>();
        List<String> list = new ArrayList<>();
        for (String f : ignoreFieldsRaw.split(",")) {
            String trimmed = f.trim();
            if (!trimmed.isEmpty()) list.add(trimmed);
        }
        return list;
    }

    public String getIgnoreFieldsRaw() { return ignoreFieldsRaw; }
    public void setIgnoreFieldsRaw(String ignoreFieldsRaw) { this.ignoreFieldsRaw = ignoreFieldsRaw; }

    public boolean isCaseSensitive() { return caseSensitive; }
    public void setCaseSensitive(boolean caseSensitive) { this.caseSensitive = caseSensitive; }

    public boolean isIgnoreArrayOrder() { return ignoreArrayOrder; }
    public void setIgnoreArrayOrder(boolean ignoreArrayOrder) { this.ignoreArrayOrder = ignoreArrayOrder; }

    public double getNumericTolerance() { return numericTolerance; }
    public void setNumericTolerance(double numericTolerance) { this.numericTolerance = numericTolerance; }

    public boolean isCompareErrorResponses() { return compareErrorResponses; }
    public void setCompareErrorResponses(boolean compareErrorResponses) { this.compareErrorResponses = compareErrorResponses; }
}