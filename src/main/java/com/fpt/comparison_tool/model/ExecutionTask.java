package com.fpt.comparison_tool.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Execution control ticket — tracks queue position, progress, status.
 * Does NOT store results. Results live in TestSuite.testCase.result.
 */
public class ExecutionTask {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String taskId;
    private final String suiteId;
    private final String suiteName;
    private final List<String> groupFilter;

    private volatile TaskStatus status = TaskStatus.PENDING;
    private volatile String currentGroup = "";
    private volatile String currentCase  = "";
    private volatile String errorMessage = null;

    private final AtomicInteger total      = new AtomicInteger(0);
    private final AtomicInteger done       = new AtomicInteger(0);
    private final AtomicInteger passed     = new AtomicInteger(0);
    private final AtomicInteger failed     = new AtomicInteger(0);
    private final AtomicInteger errorCount = new AtomicInteger(0);

    private String createdAt;
    private String startedAt;
    private String completedAt;

    public ExecutionTask(String taskId, String suiteId, String suiteName, List<String> groupFilter) {
        this.taskId      = taskId;
        this.suiteId     = suiteId;
        this.suiteName   = suiteName;
        this.groupFilter = groupFilter != null ? groupFilter : List.of();
        this.createdAt   = LocalDateTime.now().format(FMT);
    }

    // ── Progress (called from ExecutionService) ───────────────────────────────

    public void start(int totalCases) {
        this.total.set(totalCases);
        this.status    = TaskStatus.IN_PROGRESS;
        this.startedAt = LocalDateTime.now().format(FMT);
    }

    public void recordPassed(String group, String caseId) {
        passed.incrementAndGet(); done.incrementAndGet();
        this.currentGroup = group; this.currentCase = caseId;
    }

    public void recordFailed(String group, String caseId) {
        failed.incrementAndGet(); done.incrementAndGet();
        this.currentGroup = group; this.currentCase = caseId;
    }

    public void recordError(String group, String caseId) {
        errorCount.incrementAndGet(); done.incrementAndGet();
        this.currentGroup = group; this.currentCase = caseId;
    }

    public void finish() {
        this.status      = TaskStatus.COMPLETED;
        this.completedAt = LocalDateTime.now().format(FMT);
        this.currentGroup = ""; this.currentCase = "";
    }

    public void abort(String reason) {
        this.errorMessage = reason;
        this.status       = TaskStatus.FAILED;
        this.completedAt  = LocalDateTime.now().format(FMT);
    }

    public void cancel() {
        this.status      = TaskStatus.CANCELLED;
        this.completedAt = LocalDateTime.now().format(FMT);
    }

    // ── Computed ──────────────────────────────────────────────────────────────

    public int getPercent() {
        int t = total.get();
        return t == 0 ? 0 : (int) (done.get() * 100.0 / t);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getTaskId()            { return taskId; }
    public String getSuiteId()           { return suiteId; }
    public String getSuiteName()         { return suiteName; }
    public List<String> getGroupFilter() { return groupFilter; }
    public TaskStatus getStatus()        { return status; }
    public String getCurrentGroup()      { return currentGroup; }
    public String getCurrentCase()       { return currentCase; }
    public String getErrorMessage()      { return errorMessage; }
    public int getTotal()                { return total.get(); }
    public int getDone()                 { return done.get(); }
    public int getPassed()               { return passed.get(); }
    public int getFailed()               { return failed.get(); }
    public int getErrorCount()           { return errorCount.get(); }
    public String getCreatedAt()         { return createdAt; }
    public String getStartedAt()         { return startedAt; }
    public String getCompletedAt()       { return completedAt; }
}
