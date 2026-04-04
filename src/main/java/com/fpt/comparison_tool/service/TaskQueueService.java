package com.fpt.comparison_tool.service;

import com.fpt.comparison_tool.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

/**
 * Manages task lifecycle:
 *  - LinkedBlockingQueue for pending tasks (consumer thread blocks on take())
 *  - Semaphore(5) to cap concurrent executions
 *  - LinkedHashMap cap 10 for task history (evicts oldest terminal tasks)
 *  - Consumer thread runs forever, picking from queue when slot available
 */
@Service
public class TaskQueueService {

    private static final Logger log = LoggerFactory.getLogger(TaskQueueService.class);

    private static final int MAX_CONCURRENT = 5;
    private static final int MAX_HISTORY    = 10;

    private final ExecutionService executionService;
    private final SuiteRegistry    suiteRegistry;

    // Pending task IDs waiting to be picked up
    private final LinkedBlockingQueue<String> pendingQueue = new LinkedBlockingQueue<>();

    // All tasks (pending + active + history), evicts oldest terminal when > MAX_HISTORY
    private final Map<String, ExecutionTask> taskRegistry = Collections.synchronizedMap(
        new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ExecutionTask> eldest) {
                if (size() <= MAX_HISTORY) return false;
                // Only evict terminal tasks, never active ones
                return eldest.getValue().isTerminal();
            }
        }
    );

    private final Semaphore activeSlots = new Semaphore(MAX_CONCURRENT);
    private final ExecutorService workerPool = Executors.newCachedThreadPool();
    private volatile boolean running = true;

    public TaskQueueService(ExecutionService executionService, SuiteRegistry suiteRegistry) {
        this.executionService = executionService;
        this.suiteRegistry    = suiteRegistry;
    }

    @PostConstruct
    public void startConsumer() {
        // Single dispatcher thread: blocks on queue, acquires semaphore, runs task in pool
        Thread dispatcher = new Thread(this::dispatchLoop, "task-dispatcher");
        dispatcher.setDaemon(true);
        dispatcher.start();
        log.info("TaskQueueService started — max {} concurrent tasks, history cap {}",
                MAX_CONCURRENT, MAX_HISTORY);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        workerPool.shutdown();
    }

    // ── Submit ────────────────────────────────────────────────────────────────

    /**
     * Submit a new execution task. Returns immediately with the task (PENDING).
     * Throws if suite not found.
     */
    public ExecutionTask submit(String suiteId, List<String> groupFilter,
                                VerificationMode verificationMode) {
        TestSuite suite = suiteRegistry.get(suiteId)
                .orElseThrow(() -> new IllegalArgumentException("Suite not found: " + suiteId));

        String taskId = UUID.randomUUID().toString();
        ExecutionTask task = new ExecutionTask(
                taskId, suiteId,
                suite.getSettings().getSuiteName(),
                groupFilter, verificationMode);

        taskRegistry.put(taskId, task);
        pendingQueue.offer(taskId);
        log.info("Task {} submitted for suite '{}' — queue size: {}",
                taskId, task.getSuiteName(), pendingQueue.size());
        return task;
    }

    // ── Dispatch loop ─────────────────────────────────────────────────────────

    private void dispatchLoop() {
        while (running) {
            try {
                String taskId = pendingQueue.take(); // blocks until something available
                ExecutionTask task = taskRegistry.get(taskId);

                if (task == null || task.getStatus() == TaskStatus.CANCELLED) {
                    log.info("Task {} cancelled or missing — skipping", taskId);
                    continue;
                }

                activeSlots.acquire(); // blocks until a slot is free (max 5)

                workerPool.submit(() -> {
                    try {
                        runTask(task);
                    } finally {
                        activeSlots.release();
                        log.info("Task {} finished — {} slot(s) now available",
                                taskId, activeSlots.availablePermits());
                    }
                });

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void runTask(ExecutionTask task) {
        Optional<TestSuite> suiteOpt = suiteRegistry.get(task.getSuiteId());
        if (suiteOpt.isEmpty()) {
            task.abort("Suite no longer available: " + task.getSuiteId());
            return;
        }
        log.info("Starting task {} — suite '{}' groups: {}",
                task.getTaskId(), task.getSuiteName(),
                task.getGroupFilter().isEmpty() ? "all" : task.getGroupFilter());
        executionService.runTask(suiteOpt.get(), task);
    }

    // ── Query ──────────────────────────────────────────────────────────────────

    public Optional<ExecutionTask> getTask(String taskId) {
        return Optional.ofNullable(taskRegistry.get(taskId));
    }

    /** All tasks in creation order (newest last) */
    public List<ExecutionTask> getAllTasks() {
        synchronized (taskRegistry) {
            return new ArrayList<>(taskRegistry.values());
        }
    }

    /** Only PENDING or IN_PROGRESS tasks */
    public List<ExecutionTask> getActiveTasks() {
        return getAllTasks().stream()
                .filter(t -> t.getStatus().isActive())
                .toList();
    }

    public int getPendingCount() { return pendingQueue.size(); }
    public int getActiveSlots()  { return MAX_CONCURRENT - activeSlots.availablePermits(); }

    // ── Cancel ────────────────────────────────────────────────────────────────

    /** Cancel a PENDING task (IN_PROGRESS cannot be cancelled). */
    public boolean cancel(String taskId) {
        ExecutionTask task = taskRegistry.get(taskId);
        if (task == null) return false;
        if (task.getStatus() != TaskStatus.PENDING) return false;
        task.cancel();
        pendingQueue.remove(taskId); // remove from queue so dispatcher skips it
        log.info("Task {} cancelled", taskId);
        return true;
    }
}
