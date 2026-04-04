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
 * Controls concurrent suite execution:
 * - LinkedBlockingQueue  — pending task IDs (dispatcher blocks on take())
 * - Semaphore(5)         — max 5 concurrent running suites
 * - LinkedHashMap cap 10 — task history, evicts oldest terminal tasks
 */
@Service
public class TaskQueueService {

    private static final Logger log = LoggerFactory.getLogger(TaskQueueService.class);
    private static final int MAX_CONCURRENT = 5;
    private static final int MAX_HISTORY    = 10;

    private final ExecutionService executionService;
    private final SuiteRegistry    suiteRegistry;

    private final LinkedBlockingQueue<String> pendingQueue = new LinkedBlockingQueue<>();

    private final Map<String, ExecutionTask> taskRegistry = Collections.synchronizedMap(
        new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ExecutionTask> eldest) {
                return size() > MAX_HISTORY && eldest.getValue().getStatus().isTerminal();
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
    public void startDispatcher() {
        Thread dispatcher = new Thread(() -> {
            while (running) {
                try {
                    String taskId = pendingQueue.take();
                    ExecutionTask task = taskRegistry.get(taskId);
                    if (task == null || task.getStatus() == TaskStatus.CANCELLED) continue;

                    activeSlots.acquire();
                    workerPool.submit(() -> {
                        try { run(task); }
                        finally { activeSlots.release(); }
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "task-dispatcher");
        dispatcher.setDaemon(true);
        dispatcher.start();
        log.info("TaskQueueService started — max {} concurrent", MAX_CONCURRENT);
    }

    @PreDestroy
    public void shutdown() { running = false; workerPool.shutdown(); }

    // ── Submit ────────────────────────────────────────────────────────────────

    public ExecutionTask submit(String suiteId, List<String> groupFilter) {
        TestSuite suite = suiteRegistry.get(suiteId)
                .orElseThrow(() -> new IllegalArgumentException("Suite not found: " + suiteId));

        String taskId = UUID.randomUUID().toString();
        ExecutionTask task = new ExecutionTask(
                taskId, suiteId,
                suite.getSettings().getSuiteName(),
                groupFilter);

        taskRegistry.put(taskId, task);
        pendingQueue.offer(taskId);
        log.info("Task {} queued for suite '{}'", taskId, task.getSuiteName());
        return task;
    }

    private void run(ExecutionTask task) {
        suiteRegistry.get(task.getSuiteId()).ifPresentOrElse(
            suite -> executionService.runTask(suite, task),
            ()    -> task.abort("Suite no longer available")
        );
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    public Optional<ExecutionTask> getTask(String taskId) {
        return Optional.ofNullable(taskRegistry.get(taskId));
    }

    public List<ExecutionTask> getAllTasks() {
        synchronized (taskRegistry) {
            List<ExecutionTask> list = new ArrayList<>(taskRegistry.values());
            Collections.reverse(list);
            return list;
        }
    }

    public int getPendingCount()  { return pendingQueue.size(); }
    public int getRunningCount()  { return MAX_CONCURRENT - activeSlots.availablePermits(); }

    // ── Cancel ────────────────────────────────────────────────────────────────

    public boolean cancel(String taskId) {
        ExecutionTask task = taskRegistry.get(taskId);
        if (task == null || task.getStatus() != TaskStatus.PENDING) return false;
        task.cancel();
        pendingQueue.remove(taskId);
        return true;
    }
}
