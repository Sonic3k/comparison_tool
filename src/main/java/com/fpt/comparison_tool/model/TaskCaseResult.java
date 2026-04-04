package com.fpt.comparison_tool.model;

/**
 * Result of one TC within a task run.
 * Mirrors TestResult but is stored on the task, not on the TestCase.
 */
public class TaskCaseResult {

    private String caseId;
    private String caseName;
    private VerificationMode verificationMode;
    private ExecutionStatus status;
    private String modeRun;
    private String comparisonResult;
    private String assertionResult;
    private String sourceStatus;
    private String targetStatus;
    private String sourceResponse;
    private String targetResponse;
    private String executedAt;

    public TaskCaseResult() {}

    // ── Builder-style setters ─────────────────────────────────────────────────

    public TaskCaseResult id(String id)                           { this.caseId = id; return this; }
    public TaskCaseResult name(String name)                       { this.caseName = name; return this; }
    public TaskCaseResult mode(VerificationMode m)                { this.verificationMode = m; return this; }
    public TaskCaseResult status(ExecutionStatus s)               { this.status = s; return this; }
    public TaskCaseResult modeRun(String m)                       { this.modeRun = m; return this; }
    public TaskCaseResult comparisonResult(String r)              { this.comparisonResult = r; return this; }
    public TaskCaseResult assertionResult(String r)               { this.assertionResult = r; return this; }
    public TaskCaseResult sourceStatus(String s)                  { this.sourceStatus = s; return this; }
    public TaskCaseResult targetStatus(String s)                  { this.targetStatus = s; return this; }
    public TaskCaseResult sourceResponse(String r)                { this.sourceResponse = r; return this; }
    public TaskCaseResult targetResponse(String r)                { this.targetResponse = r; return this; }
    public TaskCaseResult executedAt(String t)                    { this.executedAt = t; return this; }

    // ── Getters ───────────────────────────────────────────────────────────────
    public String getCaseId()               { return caseId; }
    public String getCaseName()             { return caseName; }
    public VerificationMode getVerificationMode() { return verificationMode; }
    public ExecutionStatus getStatus()      { return status; }
    public String getModeRun()              { return modeRun; }
    public String getComparisonResult()     { return comparisonResult; }
    public String getAssertionResult()      { return assertionResult; }
    public String getSourceStatus()         { return sourceStatus; }
    public String getTargetStatus()         { return targetStatus; }
    public String getSourceResponse()       { return sourceResponse; }
    public String getTargetResponse()       { return targetResponse; }
    public String getExecutedAt()           { return executedAt; }
}
