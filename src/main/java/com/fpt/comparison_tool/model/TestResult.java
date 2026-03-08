package com.fpt.comparison_tool.model;

public class TestResult {

    private ExecutionStatus status;
    private String differences;
    private String sourceStatus;
    private String targetStatus;
    private String sourceResponse;
    private String targetResponse;
    private String executedAt;

    public TestResult() {
        this.status = ExecutionStatus.PENDING;
    }

    public TestResult(ExecutionStatus status, String differences,
                      String sourceStatus, String targetStatus,
                      String sourceResponse, String targetResponse,
                      String executedAt) {
        this.status = status;
        this.differences = differences;
        this.sourceStatus = sourceStatus;
        this.targetStatus = targetStatus;
        this.sourceResponse = sourceResponse;
        this.targetResponse = targetResponse;
        this.executedAt = executedAt;
    }

    public boolean hasResult() {
        return status != null && status != ExecutionStatus.PENDING;
    }

    public ExecutionStatus getStatus() { return status; }
    public void setStatus(ExecutionStatus status) { this.status = status; }

    public String getDifferences() { return differences; }
    public void setDifferences(String differences) { this.differences = differences; }

    public String getSourceStatus() { return sourceStatus; }
    public void setSourceStatus(String sourceStatus) { this.sourceStatus = sourceStatus; }

    public String getTargetStatus() { return targetStatus; }
    public void setTargetStatus(String targetStatus) { this.targetStatus = targetStatus; }

    public String getSourceResponse() { return sourceResponse; }
    public void setSourceResponse(String sourceResponse) { this.sourceResponse = sourceResponse; }

    public String getTargetResponse() { return targetResponse; }
    public void setTargetResponse(String targetResponse) { this.targetResponse = targetResponse; }

    public String getExecutedAt() { return executedAt; }
    public void setExecutedAt(String executedAt) { this.executedAt = executedAt; }
}