// Keyboard on the results screen: move through the list, pick steps, label, select, run again, open in the editor.
import { openCase, openFolder } from "../nav";
import { hookKey, latestHooks } from "../runs";
import { useStore } from "../store";
import type { Phase } from "../suiteOps";
import type { StepResult } from "../types";
import { computeResults } from "./compute";
import { LABELS, NOT_PASSED, labelName } from "./model";
import { runAgainInPlace } from "./rerun";

const PHASE_ORDER: Record<string, number> = { setup: 0, pre: 1, step: 2, cleanup: 3, teardown: 4 };
const key = (s: StepResult) => `${s.phase}:${s.index}`;

export function resultsKey(e: KeyboardEvent) {
  const st = useStore.getState();
  const data = computeResults(st);
  const run = data.run;
  if (!run) return;
  const order = data.order;
  const cur = st.runSel.caseId;
  const i = cur ? order.indexOf(cur) : -1;
  const go = (id?: string) => {
    if (!id) return;
    e.preventDefault();
    st.selectRun({ caseId: id, tab: st.runSel.tab });
  };
  const result = cur ? run.cases[cur] : undefined;
  const hook = st.runSel.hook ? latestHooks(run).find((h) => hookKey(h) === st.runSel.hook) : undefined;
  const steps = [...(result?.steps ?? hook?.steps ?? [])].sort((a, b) => (PHASE_ORDER[a.phase] ?? 9) - (PHASE_ORDER[b.phase] ?? 9) || a.index - b.index);
  const stepAt = steps.findIndex((s) => key(s) === st.runSel.step);
  const curStep = stepAt >= 0 ? stepAt : Math.max(0, steps.findIndex((s) => s.status !== "pass" && s.status !== "skipped"));

  switch (e.key) {
    case "j":
      return go(order[i < 0 ? 0 : Math.min(order.length - 1, i + 1)]);
    case "k":
      return go(order[i < 0 ? 0 : Math.max(0, i - 1)]);
    case "n":
    case "N": {
      const bad = order.filter((id) => NOT_PASSED.includes(data.infos.find((x) => x.id === id)?.state ?? "pass"));
      if (!bad.length) return;
      const pos = cur ? bad.indexOf(cur) : -1;
      if (e.key === "n") return go(bad[pos < 0 ? bad.findIndex((id) => order.indexOf(id) > i) : (pos + 1) % bad.length] ?? bad[0]);
      return go(bad[pos <= 0 ? bad.length - 1 : pos - 1]);
    }
    case "Enter":
      if (!cur && !hook && order.length) go(order[0]);
      return;
    case "Escape":
      if (st.maximized) {
        st.setMaximized(false);
      } else if (cur || hook) {
        st.selectRun({});
      } else if (Object.keys(st.checked).length) {
        st.clearChecked();
      }
      return;
    case " ":
    case "x":
      if (e.key === " " && (document.activeElement as HTMLElement | null)?.tagName === "BUTTON") return;
      if (cur) {
        e.preventDefault();
        st.setChecked([cur], !st.checked[cur]);
      }
      return;
    case "m":
      if (cur || hook) st.setMaximized(!st.maximized);
      return;
    case "]":
    case "[":
      if (steps.length) {
        e.preventDefault();
        const n = e.key === "]" ? Math.min(steps.length - 1, curStep + 1) : Math.max(0, curStep - 1);
        st.selectRun({ ...st.runSel, step: key(steps[n]) });
      }
      return;
    case "e": {
      const s = steps[curStep];
      if (result) {
        useStore.setState({ fromResults: { runId: run.runId, caseId: result.caseId, label: result.key } });
        if (s) openCase(result.caseId, s.phase as Phase, s.index);
        else openCase(result.caseId);
      } else if (hook) {
        useStore.setState({ fromResults: { runId: run.runId, label: `${hook.path?.join(" / ") || "Suite"} ${hook.hook}` } });
        if (hook.folderId) openFolder(hook.folderId, hook.hook as Phase, s?.index ?? 0);
        else st.select({ kind: "suite", tab: "hooks", phase: hook.hook as Phase, step: s?.index ?? 0 });
      }
      return;
    }
    case "r":
    case "R":
      if (cur) runAgainInPlace(run, [cur], { reuseSetup: e.key === "R" });
      return;
    case "0":
      if (result) st.setTriage(run.runId, [result.caseId], { label: null });
      return;
    default: {
      const l = LABELS.find((x) => x.key === e.key);
      if (l && result) st.setTriage(run.runId, [result.caseId], { label: labelName(result.triage?.label) === l.label ? null : l.id });
    }
  }
}
