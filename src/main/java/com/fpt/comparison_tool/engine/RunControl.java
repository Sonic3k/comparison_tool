package com.fpt.comparison_tool.engine;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stop switch of a run. The first stop lets running steps finish, skips what has not started and still runs
 * cleanup and teardown; a second stop also skips cleanup and teardown and interrupts calls in flight.
 */
public final class RunControl {

    private volatile boolean stop;
    private volatile boolean hardStop;
    private final Set<Thread> workers = ConcurrentHashMap.newKeySet();

    public void stop() {
        if (stop) {
            hardStop = true;
            workers.forEach(Thread::interrupt);
        }
        stop = true;
    }

    public boolean stopped() {
        return stop;
    }

    public boolean hardStopped() {
        return hardStop;
    }

    void enter() {
        workers.add(Thread.currentThread());
    }

    void leave() {
        workers.remove(Thread.currentThread());
    }
}
