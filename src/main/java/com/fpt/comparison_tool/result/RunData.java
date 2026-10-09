package com.fpt.comparison_tool.result;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A whole run: header, hook results, test case results and the summary (null while running). */
public record RunData(RunHeader header, List<HookResult> hooks, List<CaseResult> cases, RunSummary summary) {

    /**
     * Test cases finish in any order when they run in parallel; reports list them in suite order. A test case run
     * again in place appears once per attempt; the last one is its result, likewise for hooks.
     */
    public RunData {
        hooks = hooks == null ? List.of() : latestHooks(hooks);
        cases = cases == null ? List.of() : latestCases(cases);
        if (header != null && header.caseIds() != null && !header.caseIds().isEmpty() && cases.size() > 1) {
            Map<String, Integer> index = new java.util.HashMap<>();
            for (int i = 0; i < header.caseIds().size(); i++) index.putIfAbsent(header.caseIds().get(i), i);
            List<CaseResult> sorted = new ArrayList<>(cases);
            sorted.sort(java.util.Comparator.comparingInt(c -> index.getOrDefault(c.caseId(), Integer.MAX_VALUE)));
            cases = sorted;
        }
    }

    private static List<CaseResult> latestCases(List<CaseResult> all) {
        Map<String, CaseResult> last = new LinkedHashMap<>();
        for (CaseResult c : all) {
            if (c.caseId() == null) continue;
            last.remove(c.caseId());
            last.put(c.caseId(), c);
        }
        return last.size() == all.size() ? all : new ArrayList<>(last.values());
    }

    private static List<HookResult> latestHooks(List<HookResult> all) {
        Map<String, HookResult> last = new LinkedHashMap<>();
        for (HookResult h : all) last.put((h.folderId() == null ? "" : h.folderId()) + "|" + h.hook(), h);
        return last.size() == all.size() ? all : new ArrayList<>(last.values());
    }

    public Map<String, Integer> counts() {
        Map<String, Integer> c = new LinkedHashMap<>();
        for (Status s : Status.values()) c.put(s.value(), 0);
        for (CaseResult r : cases) c.merge(r.statusEnum().value(), 1, Integer::sum);
        return c;
    }

    public List<CaseResult> notPassed() {
        List<CaseResult> out = new ArrayList<>();
        for (CaseResult r : cases) if (r.statusEnum() != Status.PASS) out.add(r);
        return out;
    }
}
