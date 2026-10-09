package com.fpt.comparison_tool.web;

import com.fpt.comparison_tool.engine.RunControl;
import com.fpt.comparison_tool.engine.RunListener;
import com.fpt.comparison_tool.result.CaseResult;
import com.fpt.comparison_tool.result.HookResult;
import com.fpt.comparison_tool.result.RunData;
import com.fpt.comparison_tool.result.RunHeader;
import com.fpt.comparison_tool.result.RunSummary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs started from the UI, kept in memory (the last few) with their event stream for polling. */
@Component
public class RunStore {

    private static final int KEEP = 24;

    /** One event of a run: seq orders them; type is started, caseStarted, hook, case or finished. */
    public record Event(int seq, String type, Object data) {
    }

    /** A run in memory. */
    public static final class Live implements RunListener {
        final String runId;
        final List<Event> events = Collections.synchronizedList(new ArrayList<>());
        final List<HookResult> hooks = Collections.synchronizedList(new ArrayList<>());
        final List<CaseResult> cases = Collections.synchronizedList(new ArrayList<>());
        volatile RunHeader header;
        volatile RunSummary summary;
        volatile RunControl control;
        volatile String resultFile;

        Live(String runId) {
            this.runId = runId;
        }

        private synchronized void add(String type, Object data) {
            events.add(new Event(events.size() + 1, type, data));
        }

        @Override
        public void started(RunHeader h) {
            header = h;
            add("started", h);
        }

        @Override
        public void caseStarted(String caseId) {
            add("caseStarted", caseId);
        }

        @Override
        public void hook(HookResult r) {
            hooks.add(r);
            add("hook", r);
        }

        @Override
        public void caseFinished(CaseResult r) {
            cases.add(r);
            add("case", r);
        }

        @Override
        public void finished(RunSummary s) {
            summary = s;
            add("finished", s);
        }

        public boolean done() {
            return summary != null;
        }

        public RunData data() {
            return new RunData(header, new ArrayList<>(hooks), new ArrayList<>(cases), summary);
        }

        public List<Event> after(int seq) {
            synchronized (this) {
                if (seq >= events.size()) return List.of();
                return new ArrayList<>(events.subList(Math.max(0, seq), events.size()));
            }
        }
    }

    private final Map<String, Live> runs = Collections.synchronizedMap(new LinkedHashMap<>());

    public Live create(String tempId) {
        Live l = new Live(tempId);
        return l;
    }

    public void register(Live live) {
        synchronized (runs) {
            runs.put(live.header.runId(), live);
            while (runs.size() > KEEP) {
                String oldest = runs.keySet().iterator().next();
                Live o = runs.get(oldest);
                if (o != null && !o.done()) break;
                runs.remove(oldest);
            }
        }
    }

    public Live get(String runId) {
        return runs.get(runId);
    }

    public List<Live> all() {
        synchronized (runs) {
            return new ArrayList<>(runs.values());
        }
    }
}
