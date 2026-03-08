package com.fpt.comparison_tool.model;

public class SuiteSettings {

    private String suiteName;
    private String description;
    private String version;
    private String createdBy;
    private String createdDate;
    private String lastUpdatedBy;
    private String lastUpdatedDate;

    private ExecutionConfig executionConfig;
    private ComparisonConfig comparisonConfig;

    public SuiteSettings() {}

    public SuiteSettings(String suiteName, String description, String version,
                         String createdBy, String createdDate,
                         String lastUpdatedBy, String lastUpdatedDate,
                         ExecutionConfig executionConfig, ComparisonConfig comparisonConfig) {
        this.suiteName = suiteName;
        this.description = description;
        this.version = version;
        this.createdBy = createdBy;
        this.createdDate = createdDate;
        this.lastUpdatedBy = lastUpdatedBy;
        this.lastUpdatedDate = lastUpdatedDate;
        this.executionConfig = executionConfig;
        this.comparisonConfig = comparisonConfig;
    }

    public String getSuiteName() { return suiteName; }
    public void setSuiteName(String suiteName) { this.suiteName = suiteName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getCreatedDate() { return createdDate; }
    public void setCreatedDate(String createdDate) { this.createdDate = createdDate; }

    public String getLastUpdatedBy() { return lastUpdatedBy; }
    public void setLastUpdatedBy(String lastUpdatedBy) { this.lastUpdatedBy = lastUpdatedBy; }

    public String getLastUpdatedDate() { return lastUpdatedDate; }
    public void setLastUpdatedDate(String lastUpdatedDate) { this.lastUpdatedDate = lastUpdatedDate; }

    public ExecutionConfig getExecutionConfig() { return executionConfig; }
    public void setExecutionConfig(ExecutionConfig executionConfig) { this.executionConfig = executionConfig; }

    public ComparisonConfig getComparisonConfig() { return comparisonConfig; }
    public void setComparisonConfig(ComparisonConfig comparisonConfig) { this.comparisonConfig = comparisonConfig; }
}