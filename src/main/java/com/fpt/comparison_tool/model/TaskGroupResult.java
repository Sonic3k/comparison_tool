package com.fpt.comparison_tool.model;

import java.util.List;

/**
 * Snapshot of results for one group within a task run.
 * Stored independently from TestSuite — suite stays immutable.
 */
public class TaskGroupResult {

    private String groupName;
    private List<TaskCaseResult> caseResults;
    private int passed;
    private int failed;
    private int error;

    public TaskGroupResult() {}

    public TaskGroupResult(String groupName, List<TaskCaseResult> caseResults) {
        this.groupName   = groupName;
        this.caseResults = caseResults;
        this.passed = (int) caseResults.stream().filter(c -> c.getStatus() == ExecutionStatus.PASSED).count();
        this.failed = (int) caseResults.stream().filter(c -> c.getStatus() == ExecutionStatus.FAILED).count();
        this.error  = (int) caseResults.stream().filter(c -> c.getStatus() == ExecutionStatus.ERROR).count();
    }

    public String getGroupName()              { return groupName; }
    public List<TaskCaseResult> getCaseResults() { return caseResults; }
    public int getPassed()                    { return passed; }
    public int getFailed()                    { return failed; }
    public int getError()                     { return error; }
    public int getTotal()                     { return caseResults != null ? caseResults.size() : 0; }
}
