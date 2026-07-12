package com.fpt.comparison_tool.dto;

import java.util.List;

/**
 * Body of POST /api/execute.
 *
 * Scopes:
 *   all        — run every enabled group (default when nothing else is set)
 *   groups     — run only the named groups            → { "groups": ["User APIs"] }
 *   testcases  — run only the referenced test cases   → { "scope": "testcases", "testCases": [{"groupName":"User APIs","testCaseId":"TC002"}] }
 *   failed     — re-run every test case whose rolled-up status is failed/error
 *
 * The legacy body { "groups": [...] } still works unchanged (scope inferred).
 *
 * includeSetup (default true) applies to testcases/failed scopes: run the
 * group's setup phase before and teardown phase after the selected requests,
 * plus any Global Setup / Global Teardown groups — so extracted variables are
 * available exactly like in a full run.
 */
public class ExecutionStartRequest {

    private String scope;
    private List<String> groups;
    private List<TestCaseRef> testCases;
    private Boolean includeSetup;

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public List<String> getGroups() { return groups; }
    public void setGroups(List<String> groups) { this.groups = groups; }

    public List<TestCaseRef> getTestCases() { return testCases; }
    public void setTestCases(List<TestCaseRef> testCases) { this.testCases = testCases; }

    public Boolean getIncludeSetup() { return includeSetup; }
    public void setIncludeSetup(Boolean includeSetup) { this.includeSetup = includeSetup; }

    public static class TestCaseRef {
        private String groupName;
        private String testCaseId;

        public TestCaseRef() {}
        public TestCaseRef(String groupName, String testCaseId) {
            this.groupName = groupName;
            this.testCaseId = testCaseId;
        }

        public String getGroupName() { return groupName; }
        public void setGroupName(String groupName) { this.groupName = groupName; }

        public String getTestCaseId() { return testCaseId; }
        public void setTestCaseId(String testCaseId) { this.testCaseId = testCaseId; }
    }
}
