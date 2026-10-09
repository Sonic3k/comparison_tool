import { useMemo } from "react";
import type { ReactNode } from "react";
import {
  ChevronLeft, CircleStop, Ellipsis, FileSpreadsheet, FileText, FolderOpen, Pencil, Play, RotateCcw, Save, X,
} from "lucide-react";
import { exportReport, rerun, rerunCase, saveRunResult } from "../actions";
import { openCase, openFolder } from "../nav";
import type { RunView } from "../runs";
import { failureSignature, fmtMs, fmtTime, hookKey } from "../runs";
import { stateCounts } from "../runRows";
import { useStore } from "../store";
import type { Phase } from "../suiteOps";
import { PHASE_LABEL, find, stepsOf, updateNode, withSteps } from "../suiteOps";
import type { CaseResult, HookResult, Step, StepResult } from "../types";
import { StepResultView } from "./ResultViews";
import { TRIAGE } from "./RunsSidebar";
import { Button, DropMenu, Empty, HttpCode, Method, StatusIcon, StatusPill } from "./ui";

export function RunMain() {
  const runs = useStore((s) => s.runs);
  const activeRun = useStore((s) => s.activeRun);
  const runSel = useStore((s) => s.runSel);
  const run = runs.find((r) => r.runId === activeRun);
  if (!run) return <Empty>Pick a run on the left.</Empty>;
  if (runSel.caseId) return <CaseResultView key={run.runId + runSel.caseId} run={run} caseId={runSel.caseId} />;
  if (runSel.hook) {
    const h = run.hooks.find((x) => hookKey(x) === runSel.hook);
    if (h) return <HookResultView run={run} hook={h} />;
  }
  return <RunSummaryView run={run} />;
}

function Back() {
  return (
    <Button size="sm" variant="ghost" icon={<ChevronLeft size={14} />} onClick={() => useStore.getState().selectRun({})} title="Run summary">Summary</Button>
  );
}

// ---------------------------------------------------------------- summary

function RunSummaryView({ run }: { run: RunView }) {
  const counts = stateCounts(run);
  const groups = useMemo(() => {
    const m = new Map<string, CaseResult[]>();
    for (const c of Object.values(run.cases)) {
      if (c.status === "pass" || c.status === "skipped") continue;
      const sig = failureSignature(c);
      m.set(sig, [...(m.get(sig) ?? []), c]);
    }
    return [...m.entries()].sort((a, b) => b[1].length - a[1].length).slice(0, 12);
  }, [run.cases]);
  const triage = useMemo(() => {
    const m: Record<string, number> = {};
    for (const c of Object.values(run.cases)) if (c.triage?.label) m[c.triage.label] = (m[c.triage.label] ?? 0) + 1;
    return m;
  }, [run.cases]);
  const st = useStore.getState();
  const reusable = run.hooks.some((h) => h.hook === "setup" && h.vars && Object.keys(h.vars).length);
  const total = counts.all || 1;
  const where = run.resultFile ?? run.savedTo ?? run.fileName;
  return (
    <div className="scroll" style={{ flex: 1 }}>
      <div className="form" style={{ maxWidth: 1100 }}>
        <div className="row wrap">
          <div className="col" style={{ gap: 2 }}>
            <div style={{ fontSize: 16, fontWeight: 600 }}>{run.header.suite}</div>
            <div className="muted">
              {run.header.env}{run.header.baselineEnv ? ` compared with ${run.header.baselineEnv}` : ""} · started {fmtTime(run.header.startedAt)}
              {run.summary?.durationMs !== undefined ? ` · ${fmtMs(run.summary.durationMs)}` : ""}
              {run.header.selection && run.header.selection !== "all" ? ` · ${run.header.selection}` : ""}
              {run.header.hooks && run.header.hooks !== "run" ? ` · setup ${run.header.hooks}` : ""}
            </div>
          </div>
          <div className="row right">
            {!run.done && run.source === "live" ? (
              <Button variant="stop" icon={<CircleStop size={14} />} onClick={() => st.stopRun(run.runId)}>Stop</Button>
            ) : (
              <>
                <DropMenu align="right" trigger={({ toggle }) => <Button icon={<RotateCcw size={14} />} onClick={toggle}>Run again</Button>}
                  items={[
                    { label: `Not passed (${counts.notpass})`, onClick: () => rerun(run, "notpass", false), disabled: !counts.notpass },
                    { label: "Not passed, reusing this run's setup values", onClick: () => rerun(run, "notpass", true), disabled: !counts.notpass || !reusable },
                    { sep: true },
                    { label: `All ${counts.all} test cases`, onClick: () => rerun(run, "all", false) },
                  ]} />
                <Button icon={<Save size={14} />} onClick={() => saveRunResult(run)} title="Keep this result as a .run.jsonl file">
                  {run.triageChanged ? "Save result •" : "Save result"}
                </Button>
                <DropMenu align="right" trigger={({ toggle }) => <Button icon={<Ellipsis size={16} />} onClick={toggle} title="More" />}
                  items={[
                    { label: "Excel report", icon: <FileSpreadsheet size={14} />, onClick: () => exportReport(run, "excel") },
                    { label: "JUnit XML", icon: <FileText size={14} />, onClick: () => exportReport(run, "junit") },
                    { sep: true },
                    { label: "Close this run", icon: <X size={14} />, onClick: () => st.removeRun(run.runId) },
                  ]} />
              </>
            )}
          </div>
        </div>
        {run.summary?.error ? <div className="note bad">{run.summary.error}</div> : null}
        {run.summary?.stopped ? <div className="note warn">The run was stopped before the end.</div> : null}
        <div className="summary-bar">
          {(["pass", "fail", "error", "blocked", "skipped", "running"] as const).map((k) => counts[k] ? <div key={k} className={k} style={{ width: `${(counts[k] / total) * 100}%` }} /> : null)}
        </div>
        <div className="stat-grid">
          {(["pass", "fail", "error", "blocked", "skipped"] as const).map((k) => (
            <button key={k} className="stat" onClick={() => st.setFilter(k)}>
              <div className="v">{counts[k]}</div>
              <div className="l"><StatusIcon status={k} size={13} />{k}</div>
            </button>
          ))}
          <button className="stat" onClick={() => st.setFilter("all")}>
            <div className="v">{counts.all}</div>
            <div className="l">{run.done ? "test cases" : `${counts.queued + counts.running} to go`}</div>
          </button>
        </div>
        {where ? <div className="faint" style={{ fontSize: 12 }}>Result file: <span className="mono">{where}</span></div> : null}
        {!where && run.done && run.source === "live" ? (
          <div className="note">This result lives only in this browser tab until you save it. Choose a results folder in the menu to save every run automatically.</div>
        ) : null}

        {groups.length ? (
          <>
            <div className="section-title">Failures by cause</div>
            <div className="card">
              <table className="table">
                <tbody>
                  {groups.map(([sig, list]) => (
                    <tr key={sig} className="click" onClick={() => {
                      st.setGrouping("groups");
                      st.setFilter("notpass");
                      st.selectRun({ caseId: list[0].caseId });
                    }}>
                      <td style={{ width: 22 }}><StatusIcon status={list[0].status} size={14} /></td>
                      <td className="mono" style={{ fontSize: 12, wordBreak: "break-all" }}>{sig}</td>
                      <td className="num">{list.length}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        ) : null}

        {Object.keys(triage).length ? (
          <div className="row wrap">
            <span className="section-title" style={{ margin: 0 }}>Triage</span>
            {Object.entries(triage).map(([k, n]) => <span key={k} className="badge primary">{TRIAGE.find((t) => t.id === k)?.label ?? k}: {n}</span>)}
          </div>
        ) : null}

        {run.hooks.length ? (
          <>
            <div className="section-title">Setup and teardown</div>
            <div className="card">
              <table className="table">
                <tbody>
                  {run.hooks.map((h) => (
                    <tr key={hookKey(h)} className="click" onClick={() => st.selectRun({ hook: hookKey(h) })}>
                      <td style={{ width: 22 }}><StatusIcon status={h.status} size={14} /></td>
                      <td>{h.path?.length ? h.path.join(" / ") : "Suite"} · {h.hook}</td>
                      <td className="muted">{h.reason ?? ""}</td>
                      <td className="num">{fmtMs(h.durationMs)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        ) : null}
        <div className="faint" style={{ fontSize: 12 }}>
          Run {run.runId}{run.header.tool ? ` · ${run.header.tool}` : ""}{run.header.concurrency ? ` · ${run.header.concurrency} at a time` : ""}
          {run.header.capture ? ` · bodies kept: ${run.header.capture}` : ""}
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- one test case

export function defaultStep(steps?: StepResult[]): number {
  if (!steps?.length) return -1;
  const bad = steps.findIndex((s) => s.status !== "pass" && s.status !== "skipped");
  return bad >= 0 ? bad : 0;
}

function CaseResultView({ run, caseId }: { run: RunView; caseId: string }) {
  const runSel = useStore((s) => s.runSel);
  const suite = useStore((s) => s.suite);
  const c = run.cases[caseId];
  const st = useStore.getState();
  const inSuite = !!suite && !!find(suite, caseId);

  if (!c) {
    const loc = suite ? find(suite, caseId) : null;
    const running = !!run.running[caseId];
    return (
      <div className="pane">
        <div className="head"><Back /><StatusIcon status={running ? "running" : "queued"} /><span>{loc?.node.type === "case" ? `${loc.node.key} ${loc.node.name}` : caseId}</span></div>
        <Empty>{running ? "Running…" : "Waiting for its turn."}</Empty>
      </div>
    );
  }

  const steps = c.steps ?? [];
  const idx = runSel.step !== undefined && runSel.step < steps.length ? runSel.step : defaultStep(steps);
  const step = idx >= 0 ? steps[idx] : undefined;

  const addToSuite = (s: StepResult, kind: "assert" | "extract", a: string, b?: string) => {
    const store = useStore.getState();
    if (!store.suite || !find(store.suite, caseId)) return store.toast("error", "This test case is not in the open suite");
    const phase = s.phase as Phase;
    store.edit((suite0) => updateNode(suite0, caseId, (n) => {
      if (n.type !== "case") return n;
      const list = stepsOf(n, phase);
      const target = list[s.index];
      if (!target) return n;
      const changed: Step = kind === "assert"
        ? { ...target, expect: { ...target.expect, assertions: [...(target.expect?.assertions ?? []).filter((x) => x !== a), a] } }
        : { ...target, extract: [...(target.extract ?? []), { name: a, path: b! }] };
      return withSteps(n, phase, list.map((x, i) => (i === s.index ? changed : x)));
    }));
    store.toast("ok", kind === "assert" ? `Check added to step ${s.index + 1} of ${c.key}: ${a}` : `Step ${s.index + 1} of ${c.key} now saves {{${a}}}`);
  };

  let lastPhase = "";
  return (
    <div className="pane">
      <div className="head" style={{ flexWrap: "wrap", rowGap: 6 }}>
        <Back />
        <StatusPill status={c.status} />
        <span className="mono muted">{c.key}</span>
        <span style={{ fontWeight: 600, fontSize: 14 }} className="ellipsis grow">{c.name !== c.key ? c.name : ""}</span>
        <Button icon={<Pencil size={14} />} disabled={!inSuite} onClick={() => step ? openCase(caseId, step.phase as Phase, step.index) : openCase(caseId)} title="Open in the editor (E)">Edit</Button>
        <Button icon={<Play size={14} />} disabled={!inSuite} onClick={() => rerunCase(run, caseId)} title="Run this test case again (R)">Run again</Button>
        <DropMenu align="right" trigger={({ toggle }) => <Button variant="ghost" icon={<Ellipsis size={16} />} onClick={toggle} title="More" />}
          items={[
            { label: "Run again reusing this run's setup values", icon: <RotateCcw size={14} />, disabled: !inSuite, onClick: () => rerunCase(run, caseId, true) },
            ...(c.folderId ? [{ label: "Open the folder", icon: <FolderOpen size={14} />, onClick: () => openFolder(c.folderId!) }] : []),
          ]} />
      </div>
      <div className="scroll" style={{ flex: 1 }}>
        <div style={{ padding: "10px 20px 0" }} className="col">
          <div className="muted" style={{ fontSize: 12 }}>
            {c.path?.length ? c.path.join(" / ") + " · " : ""}{fmtTime(c.startedAt)} · {fmtMs(c.durationMs)}
            {c.tags?.length ? ` · #${c.tags.join(" #")}` : ""}
          </div>
          {c.reason ? <div className={`note ${c.status === "error" ? "warn" : c.status === "pass" ? "" : "bad"}`}>{c.reason}</div> : null}
          {c.cleanupFailed ? <div className="note warn">Cleanup did not pass; test data may be left behind.</div> : null}
          <Triage run={run} c={c} />
        </div>
        <div style={{ padding: "8px 12px 0" }}>
          {steps.length ? (
            <table className="steps-table">
              <tbody>
                {steps.map((s, i) => {
                  const header = s.phase !== lastPhase && (s.phase !== "step" || steps.some((x) => x.phase !== "step"));
                  lastPhase = s.phase;
                  return (
                    <FragmentRows key={i} header={header ? PHASE_LABEL[s.phase as Phase] ?? s.phase : null}>
                      <tr className={i === idx ? "active" : undefined} onClick={() => st.selectRun({ ...useStore.getState().runSel, caseId, step: i })}>
                        <td style={{ width: 26 }}><StatusIcon status={s.status} size={14} /></td>
                        <td style={{ width: 24 }} className="faint">{s.index + 1}</td>
                        <td style={{ maxWidth: 320, overflow: "hidden", textOverflow: "ellipsis" }}>{s.name}</td>
                        <td style={{ width: 44 }}><Method m={s.target?.method} /></td>
                        <td className="url" title={s.target?.url}>{s.target?.url ?? s.reason ?? ""}</td>
                        <td style={{ width: 48 }}><HttpCode code={s.target?.status} /></td>
                        <td style={{ width: 70, textAlign: "right" }} className="faint">{fmtMs(s.durationMs)}</td>
                      </tr>
                    </FragmentRows>
                  );
                })}
              </tbody>
            </table>
          ) : <div className="muted" style={{ padding: 12 }}>No steps ran.</div>}
        </div>
        {step ? (
          <div className="card" style={{ margin: "12px 12px 24px" }}>
            <StepResultView r={step} tab={runSel.tab} onTab={(t) => st.selectRun({ ...useStore.getState().runSel, tab: t })}
              actions={inSuite && (step.phase === "step" || step.phase === "pre" || step.phase === "cleanup") ? {
                onAssert: (line) => addToSuite(step, "assert", line),
                onExtract: (name, path) => addToSuite(step, "extract", name, path),
              } : undefined} />
          </div>
        ) : null}
      </div>
    </div>
  );
}

function FragmentRows({ header, children }: { header: string | null; children: ReactNode }) {
  return (
    <>
      {header ? (
        <tr className="phase-row"><td colSpan={7}>{header}</td></tr>
      ) : null}
      {children}
    </>
  );
}

function Triage({ run, c }: { run: RunView; c: CaseResult }) {
  const st = useStore.getState();
  const label = c.triage?.label;
  if (c.status === "pass" && !label && !c.triage?.note) return null;
  return (
    <div className="triage">
      <span className="faint" style={{ fontSize: 12, marginRight: 4 }}>Triage</span>
      {TRIAGE.map((t) => (
        <button key={t.id} className={`chip${label === t.id ? " on" : ""}`} onClick={() => st.setTriage(run.runId, c.caseId, label === t.id ? undefined : t.id, c.triage?.note)}>
          <kbd>{t.key}</kbd>{t.label}
        </button>
      ))}
      <input className="input sm" style={{ width: 260 }} placeholder="Note (ticket, cause…)" value={c.triage?.note ?? ""}
        onChange={(e) => st.setTriage(run.runId, c.caseId, label, e.target.value)} />
    </div>
  );
}

// ---------------------------------------------------------------- setup / teardown

function HookResultView({ run, hook }: { run: RunView; hook: HookResult }) {
  const runSel = useStore((s) => s.runSel);
  const st = useStore.getState();
  const steps = hook.steps ?? [];
  const idx = runSel.step !== undefined && runSel.step < steps.length ? runSel.step : defaultStep(steps);
  const step = idx >= 0 ? steps[idx] : undefined;
  const lanes = Object.entries(hook.vars ?? {});
  return (
    <div className="pane">
      <div className="head">
        <Back />
        <StatusPill status={hook.status} />
        <span style={{ fontWeight: 600 }} className="grow ellipsis">{hook.path?.length ? hook.path.join(" / ") : "Suite"} · {hook.hook}</span>
        <Button icon={<Pencil size={14} />} onClick={() => {
          if (hook.folderId) openFolder(hook.folderId, hook.hook as Phase, step?.index ?? 0);
          else st.select({ kind: "suite", tab: "hooks", phase: hook.hook as Phase, step: step?.index ?? 0 });
        }}>Edit</Button>
      </div>
      <div className="scroll" style={{ flex: 1 }}>
        <div style={{ padding: "10px 20px 0" }} className="col">
          <div className="muted" style={{ fontSize: 12 }}>{fmtTime(hook.startedAt)} · {fmtMs(hook.durationMs)} · run {run.runId}</div>
          {hook.reason ? <div className="note bad">{hook.reason}</div> : null}
          {lanes.length && lanes.some(([, v]) => Object.keys(v).length) ? (
            <div className="card card-pad">
              <div className="section-title" style={{ marginTop: 0, marginBottom: 6 }}>Values available to the test cases</div>
              <div className={lanes.length > 1 ? "lanes" : undefined}>
                {lanes.map(([lane, vars]) => (
                  <div key={lane}>
                    {lanes.length > 1 ? <div className="lane-title">{lane}</div> : null}
                    <div className="kvlist">
                      {Object.entries(vars).map(([k, v]) => (
                        <FragmentKV key={k} k={k} v={v} />
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          ) : null}
        </div>
        <div style={{ padding: "8px 12px 0" }}>
          <table className="steps-table">
            <tbody>
              {steps.map((s, i) => (
                <tr key={i} className={i === idx ? "active" : undefined} onClick={() => st.selectRun({ ...useStore.getState().runSel, step: i })}>
                  <td style={{ width: 26 }}><StatusIcon status={s.status} size={14} /></td>
                  <td style={{ width: 24 }} className="faint">{s.index + 1}</td>
                  <td style={{ maxWidth: 320, overflow: "hidden", textOverflow: "ellipsis" }}>{s.name}</td>
                  <td style={{ width: 44 }}><Method m={s.target?.method} /></td>
                  <td className="url" title={s.target?.url}>{s.target?.url ?? s.reason ?? ""}</td>
                  <td style={{ width: 48 }}><HttpCode code={s.target?.status} /></td>
                  <td style={{ width: 70, textAlign: "right" }} className="faint">{fmtMs(s.durationMs)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {step ? (
          <div className="card" style={{ margin: "12px 12px 24px" }}>
            <StepResultView r={step} tab={runSel.tab} onTab={(t) => st.selectRun({ ...useStore.getState().runSel, tab: t })} />
          </div>
        ) : null}
      </div>
    </div>
  );
}

function FragmentKV({ k, v }: { k: string; v: string }) {
  return (
    <>
      <div className="k">{k}</div>
      <div className="v">{v}</div>
    </>
  );
}
