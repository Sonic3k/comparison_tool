// Running test cases again inside the run being looked at: the server runs them as a separate, unsaved run and
// each new result becomes the latest attempt of its test case here; the earlier attempts stay in the run's history.
import { api } from "../api";
import type { RunView } from "../runs";
import { latestHooks } from "../runs";
import { patchRun, saveQuietly, useStore } from "../store";
import { find } from "../suiteOps";
import type { CaseResult, HookResult, RunSummary, Selection } from "../types";
import { reuseMap } from "../components/Dialogs";

export interface RerunOptions {
  /** Seed the folders with the setup values of this run instead of running the setups again. */
  reuseSetup?: boolean;
}

export async function runAgainInPlace(run: RunView, caseIds: string[], opts: RerunOptions = {}): Promise<void> {
  const st = useStore.getState();
  if (!st.suite) return st.toast("error", "Open the suite first: running again needs its test cases");
  if (!run.done) return st.toast("info", "This run is still going. Run again when it has finished.");
  if (run.rerun) return st.toast("info", "Some test cases of this run are already running again");
  const ids = [...new Set(caseIds)].filter((id) => find(st.suite!, id)?.node.type === "case");
  if (!ids.length) return st.toast("error", caseIds.length ? "These test cases are not in the open suite" : "Nothing to run again");
  const envs = (st.suite.environments ?? []).map((e) => e.name);
  if (!envs.includes(run.header.env) || (run.header.baselineEnv && !envs.includes(run.header.baselineEnv))) {
    return st.toast("error", `The suite has no environment ${run.header.env}${run.header.baselineEnv ? ` or ${run.header.baselineEnv}` : ""}`);
  }
  const hooks = opts.reuseSetup ? "reuse" : "run";
  try {
    const res = await api.startRun({
      suite: st.suite, env: run.header.env, baselineEnv: run.header.baselineEnv, selection: { caseIds: ids },
      options: { capture: "all", hooks, reuse: opts.reuseSetup ? reuseMap(latestHooks(run)) : undefined, parent: run.runId },
      persist: false,
    });
    const pending: Record<string, true> = {};
    for (const id of ids) pending[id] = true;
    patchRun(run.runId, (r) => ({ ...r, pending, rerun: { runId: res.runId, nextSeq: 0, caseIds: ids, startedAt: res.header.startedAt, hooks } }));
    follow(run.runId, res.runId);
  } catch (e) {
    st.toast("error", (e as Error).message);
  }
}

/** Starts a new run of the same test cases from scratch: a separate result, this one is left as it is. */
export function startOver(run: RunView) {
  const st = useStore.getState();
  if (!st.suite) return st.toast("error", "Open the suite first");
  const envs = (st.suite.environments ?? []).map((e) => e.name);
  if (envs.includes(run.header.env)) {
    st.setEnv(run.header.env, run.header.baselineEnv && envs.includes(run.header.baselineEnv) ? run.header.baselineEnv : undefined);
  }
  const selection: Selection | undefined = run.header.selection === "all" ? undefined : { caseIds: run.header.caseIds ?? Object.keys(run.cases) };
  st.startRun(selection);
}

async function follow(parentId: string, childId: string) {
  let failures = 0;
  for (;;) {
    const parent = useStore.getState().runs.find((r) => r.runId === parentId);
    if (!parent?.rerun) return;
    let res;
    try {
      res = await api.events(childId, parent.rerun.nextSeq);
      failures = 0;
    } catch (e) {
      if (++failures < 5) {
        await new Promise((r) => setTimeout(r, 800 * failures));
        continue;
      }
      useStore.getState().toast("error", "Lost the test cases running again: " + (e as Error).message);
      patchRun(parentId, (r) => ({ ...r, rerun: undefined, pending: undefined, running: {} }));
      return;
    }
    let summary: RunSummary | undefined;
    if (res.events.length) {
      patchRun(parentId, (r) => {
        const next: RunView = { ...r, cases: { ...r.cases }, history: { ...r.history }, running: { ...r.running }, pending: { ...r.pending }, hooks: [...r.hooks] };
        for (const ev of res.events) {
          if (ev.type === "caseStarted") {
            const id = ev.data as string;
            next.running[id] = true;
            delete next.pending![id];
          } else if (ev.type === "hook") {
            const h = ev.data as HookResult;
            next.hooks.push({ ...h, attempt: hookAttempts(next, h) + 1 });
          } else if (ev.type === "case") {
            const c = ev.data as CaseResult;
            // a repeat that was stopped before a test case ran leaves its earlier result as it was
            if (c.status === "skipped" && /^Run stopped/.test(c.reason ?? "")) {
              delete next.running[c.caseId];
              delete next.pending![c.caseId];
            } else merge(next, c);
          }
          else if (ev.type === "finished") summary = ev.data as RunSummary;
        }
        next.rerun = { ...r.rerun!, nextSeq: res.next };
        return next;
      });
    }
    if (res.done) {
      patchRun(parentId, (r) => {
        const rr = r.rerun!;
        const reruns = [...(r.header.reruns ?? []), { runId: rr.runId, startedAt: rr.startedAt, finishedAt: summary?.finishedAt, caseIds: rr.caseIds, hooks: rr.hooks }];
        return { ...r, header: { ...r.header, reruns }, rerun: undefined, pending: undefined, running: {}, dirty: true };
      });
      const fin = useStore.getState().runs.find((r) => r.runId === parentId);
      if (fin) {
        const ids = fin.header.reruns?.[fin.header.reruns.length - 1]?.caseIds ?? [];
        const now = ids.map((id) => fin.cases[id]?.status);
        const passed = now.filter((s) => s === "pass").length;
        useStore.getState().toast(passed === ids.length ? "ok" : "error",
          ids.length === 1 ? `${fin.cases[ids[0]]?.key ?? "Test case"} ran again: ${now[0] ?? "no result"}` : `Ran ${ids.length} test cases again: ${passed} passed, ${ids.length - passed} did not pass`);
        // saved where the run came from when that needs no prompt; otherwise Save result shows a dot
        await saveQuietly(parentId);
      }
      return;
    }
    await new Promise((r) => setTimeout(r, res.events.length ? 200 : 450));
  }
}

function hookAttempts(r: RunView, h: HookResult): number {
  return r.hooks.filter((x) => (x.folderId ?? "") === (h.folderId ?? "") && x.hook === h.hook).length;
}

/** Makes a new result the latest attempt of its test case, keeping the earlier one in the history. */
function merge(run: RunView, c: CaseResult) {
  const prev = run.cases[c.caseId];
  if (prev) run.history[c.caseId] = [...(run.history[c.caseId] ?? []), { ...prev, attempt: prev.attempt ?? 1 }];
  run.cases[c.caseId] = { ...c, attempt: (prev?.attempt ?? (prev ? 1 : 0)) + 1, triage: prev?.triage };
  delete run.running[c.caseId];
  if (run.pending) delete run.pending[c.caseId];
  if (!(run.header.caseIds ?? []).includes(c.caseId)) {
    run.header = {
      ...run.header,
      caseIds: [...(run.header.caseIds ?? []), c.caseId],
      disabled: run.header.disabled?.filter((d) => d.caseId !== c.caseId),
    };
  }
}
