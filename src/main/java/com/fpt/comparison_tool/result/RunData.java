package com.fpt.comparison_tool.result;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A whole run: header, hook results, test case results and the summary (null while running). */
public record RunData(RunHeader header, List<HookResult> hooks, List<CaseResult> cases, RunSummary summary) {

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
