package com.fpt.comparison_tool.model;

public class TestResult {

    // ── Shared ────────────────────────────────────────────────────────────────
    private ExecutionStatus status;       // overall: passed / failed / error / pending
    private String modeRun;              // which mode actually ran: comparison / automation / both
    private String executedAt;

    // ── Comparison block (populated when mode = comparison or both) ───────────
    private String comparisonResult;     // human-readable diffs, empty = identical
    private String sourceStatus;
    private String targetStatus;
    private String sourceResponse;       // stored for XML / UI; not written to Excel
    private String targetResponse;       // stored for XML / UI; not written to Excel

    // ── Automation block (populated when mode = automation or both) ───────────
    private String assertionResult;      // e.g. "3/4 passed — $.status expected 'active' got 'inactive'"

    public TestResult() {
        this.status = ExecutionStatus.PENDING;
    }

    public boolean hasResult() {
        return status != null && status != ExecutionStatus.PENDING;
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public ExecutionStatus getStatus() { return status; }
    public void setStatus(ExecutionStatus status) { this.status = status; }

    public String getModeRun() { return modeRun; }
    public void setModeRun(String modeRun) { this.modeRun = modeRun; }

    public String getExecutedAt() { return executedAt; }
    public void setExecutedAt(String executedAt) { this.executedAt = executedAt; }

    public String getComparisonResult() { return comparisonResult; }
    public void setComparisonResult(String comparisonResult) { this.comparisonResult = comparisonResult; }

    public String getSourceStatus() { return sourceStatus; }
    public void setSourceStatus(String sourceStatus) { this.sourceStatus = sourceStatus; }

    public String getTargetStatus() { return targetStatus; }
    public void setTargetStatus(String targetStatus) { this.targetStatus = targetStatus; }

    public String getSourceResponse() { return sourceResponse; }
    public void setSourceResponse(String sourceResponse) { this.sourceResponse = sourceResponse; }

    public String getTargetResponse() { return targetResponse; }
    public void setTargetResponse(String targetResponse) { this.targetResponse = targetResponse; }

    public String getAssertionResult() { return assertionResult; }
    public void setAssertionResult(String assertionResult) { this.assertionResult = assertionResult; }

    // ── Legacy compat — differences alias ────────────────────────────────────
    public String getDifferences() { return comparisonResult; }
    public void setDifferences(String d) { this.comparisonResult = d; }
}
