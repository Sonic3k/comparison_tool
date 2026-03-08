package com.fpt.comparison_tool.dto;

import java.util.concurrent.atomic.AtomicInteger;

public class ExecutionProgress {

    private volatile boolean running = false;
    private volatile boolean completed = false;
    private volatile String currentGroup = "";
    private volatile String currentCase = "";
    private volatile String error = null;

    private final AtomicInteger total     = new AtomicInteger(0);
    private final AtomicInteger done      = new AtomicInteger(0);
    private final AtomicInteger passed    = new AtomicInteger(0);
    private final AtomicInteger failed    = new AtomicInteger(0);
    private final AtomicInteger errorCount = new AtomicInteger(0);

    public void start(int totalCases) {
        this.total.set(totalCases);
        this.done.set(0);
        this.passed.set(0);
        this.failed.set(0);
        this.errorCount.set(0);
        this.running = true;
        this.completed = false;
        this.error = null;
    }

    public void recordPassed(String groupName, String caseId) {
        passed.incrementAndGet();
        done.incrementAndGet();
        this.currentGroup = groupName;
        this.currentCase = caseId;
    }

    public void recordFailed(String groupName, String caseId) {
        failed.incrementAndGet();
        done.incrementAndGet();
        this.currentGroup = groupName;
        this.currentCase = caseId;
    }

    public void recordError(String groupName, String caseId) {
        errorCount.incrementAndGet();
        done.incrementAndGet();
        this.currentGroup = groupName;
        this.currentCase = caseId;
    }

    public void finish() {
        this.running = false;
        this.completed = true;
    }

    public void abort(String reason) {
        this.running = false;
        this.completed = true;
        this.error = reason;
    }

    public void reset() {
        this.running = false;
        this.completed = false;
        this.error = null;
        this.currentGroup = "";
        this.currentCase = "";
        this.total.set(0);
        this.done.set(0);
        this.passed.set(0);
        this.failed.set(0);
        this.errorCount.set(0);
    }

    // ─── Getters ──────────────────────────────────────────────────────────────

    public boolean isRunning()    { return running; }
    public boolean isCompleted()  { return completed; }
    public String getCurrentGroup() { return currentGroup; }
    public String getCurrentCase()  { return currentCase; }
    public String getError()      { return error; }
    public int getTotal()         { return total.get(); }
    public int getDone()          { return done.get(); }
    public int getPassed()        { return passed.get(); }
    public int getFailed()        { return failed.get(); }
    public int getErrorCount()    { return errorCount.get(); }

    public int getPercent() {
        int t = total.get();
        return t == 0 ? 0 : (int) (done.get() * 100.0 / t);
    }
}