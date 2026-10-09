package com.fpt.comparison_tool.engine;

import com.fpt.comparison_tool.result.CaseResult;
import com.fpt.comparison_tool.result.HookResult;
import com.fpt.comparison_tool.result.RunHeader;
import com.fpt.comparison_tool.result.RunSummary;

import java.util.List;

/** Receives run events; implementations must be thread-safe (test cases finish on many threads). */
public interface RunListener {

    default void started(RunHeader header) {
    }

    default void caseStarted(String caseId) {
    }

    default void hook(HookResult result) {
    }

    default void caseFinished(CaseResult result) {
    }

    default void finished(RunSummary summary) {
    }

    static RunListener of(List<RunListener> listeners) {
        return new RunListener() {
            @Override
            public void started(RunHeader header) {
                listeners.forEach(l -> l.started(header));
            }

            @Override
            public void caseStarted(String caseId) {
                listeners.forEach(l -> l.caseStarted(caseId));
            }

            @Override
            public void hook(HookResult result) {
                listeners.forEach(l -> l.hook(result));
            }

            @Override
            public void caseFinished(CaseResult result) {
                listeners.forEach(l -> l.caseFinished(result));
            }

            @Override
            public void finished(RunSummary summary) {
                listeners.forEach(l -> l.finished(summary));
            }
        };
    }
}
