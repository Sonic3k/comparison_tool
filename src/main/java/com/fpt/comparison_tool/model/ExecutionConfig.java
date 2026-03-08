package com.fpt.comparison_tool.model;

public class ExecutionConfig {

    private ExecutionMode mode;
    private int timeout;
    private int parallelLimit;
    private int delayBetweenRequests;
    private int retries;
    private String sourceEnvironment;
    private String targetEnvironment;

    public ExecutionConfig() {}

    /** Legacy 5-arg constructor — kept for backward compatibility */
    public ExecutionConfig(ExecutionMode mode, int timeout, int parallelLimit,
                           int delayBetweenRequests, int retries) {
        this.mode                 = mode;
        this.timeout              = timeout;
        this.parallelLimit        = parallelLimit;
        this.delayBetweenRequests = delayBetweenRequests;
        this.retries              = retries;
    }

    public ExecutionConfig(ExecutionMode mode, int timeout, int parallelLimit,
                           int delayBetweenRequests, int retries,
                           String sourceEnvironment, String targetEnvironment) {
        this(mode, timeout, parallelLimit, delayBetweenRequests, retries);
        this.sourceEnvironment = sourceEnvironment;
        this.targetEnvironment = targetEnvironment;
    }

    public ExecutionMode getMode()             { return mode; }
    public void setMode(ExecutionMode mode)    { this.mode = mode; }

    public int getTimeout()                    { return timeout; }
    public void setTimeout(int v)              { this.timeout = v; }

    public int getParallelLimit()              { return parallelLimit; }
    public void setParallelLimit(int v)        { this.parallelLimit = v; }

    public int getDelayBetweenRequests()       { return delayBetweenRequests; }
    public void setDelayBetweenRequests(int v) { this.delayBetweenRequests = v; }

    public int getRetries()                    { return retries; }
    public void setRetries(int v)              { this.retries = v; }

    public String getSourceEnvironment()       { return sourceEnvironment; }
    public void setSourceEnvironment(String v) { this.sourceEnvironment = v; }

    public String getTargetEnvironment()       { return targetEnvironment; }
    public void setTargetEnvironment(String v) { this.targetEnvironment = v; }
}