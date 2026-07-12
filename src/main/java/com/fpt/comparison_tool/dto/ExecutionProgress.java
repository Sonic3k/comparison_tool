package com.fpt.comparison_tool.dto;

import com.fpt.comparison_tool.model.ExecutionStatus;
import com.fpt.comparison_tool.model.TestRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Live progress of the current execution. Polled by the frontend every second.
 *
 * Beyond counters, it exposes everything a UI needs to show what is going on
 * right now:
 *   - scopeKeys : the full ordered plan of this run ("group::requestId") — lets
 *                 the UI mark rows as queued the moment the run starts
 *   - active    : requests currently in flight
 *   - recent    : ring buffer (last 100) of finished requests with status —
 *                 lets the UI update row statuses without refetching the suite
 *   - state     : idle | running | stopping | done | stopped | aborted
 */
public class ExecutionProgress {

    public static final String KEY_SEP = "::";

    public static String key(String group, String requestId) {
        return group + KEY_SEP + requestId;
    }

    /** One finished request — pushed to the recent feed. */
    public record RecentEntry(String group, String requestId, String testCaseId,
                              String status, long at) {}

    private volatile boolean running = false;
    private volatile boolean completed = false;
    private volatile boolean stopRequested = false;
    private volatile boolean stopped = false;
    private volatile String error = null;
    private volatile String currentGroup = "";
    private volatile String currentCase = "";
    private volatile long startedAt = 0;
    private volatile long finishedAt = 0;

    private final AtomicInteger total      = new AtomicInteger(0);
    private final AtomicInteger done       = new AtomicInteger(0);
    private final AtomicInteger passed     = new AtomicInteger(0);
    private final AtomicInteger failed     = new AtomicInteger(0);
    private final AtomicInteger errorCount = new AtomicInteger(0);

    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final List<String> scopeKeys = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedDeque<RecentEntry> recent = new ConcurrentLinkedDeque<>();

    private static final int RECENT_LIMIT = 100;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void start(int totalCases) {
        start(totalCases, Collections.emptyList());
    }

    public void start(int totalCases, List<String> plannedKeys) {
        this.total.set(totalCases);
        this.done.set(0);
        this.passed.set(0);
        this.failed.set(0);
        this.errorCount.set(0);
        this.active.clear();
        this.recent.clear();
        this.scopeKeys.clear();
        if (plannedKeys != null) this.scopeKeys.addAll(plannedKeys);
        this.running = true;
        this.completed = false;
        this.stopRequested = false;
        this.stopped = false;
        this.error = null;
        this.startedAt = System.currentTimeMillis();
        this.finishedAt = 0;
    }

    /** Graceful stop: in-flight requests finish, remaining are skipped, teardown still runs. */
    public void requestStop() {
        if (running) this.stopRequested = true;
    }

    public void finish() {
        this.running = false;
        this.completed = true;
        this.stopped = stopRequested;
        this.finishedAt = System.currentTimeMillis();
        this.active.clear();
    }

    public void abort(String reason) {
        this.running = false;
        this.completed = true;
        this.error = reason;
        this.finishedAt = System.currentTimeMillis();
        this.active.clear();
    }

    public void reset() {
        this.running = false;
        this.completed = false;
        this.stopRequested = false;
        this.stopped = false;
        this.error = null;
        this.currentGroup = "";
        this.currentCase = "";
        this.startedAt = 0;
        this.finishedAt = 0;
        this.total.set(0);
        this.done.set(0);
        this.passed.set(0);
        this.failed.set(0);
        this.errorCount.set(0);
        this.active.clear();
        this.recent.clear();
        this.scopeKeys.clear();
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    public void recordStart(String groupName, TestRequest r) {
        active.add(key(groupName, r.getId()));
        this.currentGroup = groupName;
        this.currentCase = r.getId();
    }

    public void recordPassed(String groupName, TestRequest r) {
        passed.incrementAndGet();
        record(groupName, r, ExecutionStatus.PASSED);
    }

    public void recordFailed(String groupName, TestRequest r) {
        failed.incrementAndGet();
        record(groupName, r, ExecutionStatus.FAILED);
    }

    public void recordError(String groupName, TestRequest r) {
        errorCount.incrementAndGet();
        record(groupName, r, ExecutionStatus.ERROR);
    }

    private void record(String groupName, TestRequest r, ExecutionStatus status) {
        done.incrementAndGet();
        active.remove(key(groupName, r.getId()));
        this.currentGroup = groupName;
        this.currentCase = r.getId();
        recent.addLast(new RecentEntry(groupName, r.getId(), r.getTestCaseId(),
                status.name().toLowerCase(), System.currentTimeMillis()));
        while (recent.size() > RECENT_LIMIT) recent.pollFirst();
    }

    // ── Getters (serialized to the frontend) ──────────────────────────────────

    public boolean isRunning()       { return running; }
    public boolean isCompleted()     { return completed; }
    public boolean isStopRequested() { return stopRequested; }
    public boolean isStopped()       { return stopped; }
    public String getError()         { return error; }
    public String getCurrentGroup()  { return currentGroup; }
    public String getCurrentCase()   { return currentCase; }
    public long getStartedAt()       { return startedAt; }
    public long getFinishedAt()      { return finishedAt; }
    public int getTotal()            { return total.get(); }
    public int getDone()             { return done.get(); }
    public int getPassed()           { return passed.get(); }
    public int getFailed()           { return failed.get(); }
    public int getErrorCount()       { return errorCount.get(); }

    public String getState() {
        if (running)   return stopRequested ? "stopping" : "running";
        if (completed) {
            if (error != null) return "aborted";
            return stopped ? "stopped" : "done";
        }
        return "idle";
    }

    public List<String> getActive()    { return new ArrayList<>(active); }
    public List<String> getScopeKeys() { return new ArrayList<>(scopeKeys); }
    public List<RecentEntry> getRecent() { return new ArrayList<>(recent); }

    public long getElapsedMs() {
        if (startedAt == 0) return 0;
        return (finishedAt > 0 ? finishedAt : System.currentTimeMillis()) - startedAt;
    }

    public int getPercent() {
        int t = total.get();
        return t == 0 ? 0 : (int) (done.get() * 100.0 / t);
    }
}
