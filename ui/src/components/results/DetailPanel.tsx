import { useEffect, useMemo, useRef, useState } from "react";
import {
  ChevronDown, ChevronLeft, ChevronRight, CircleMinus, ListEnd, ListStart, LoaderCircle, Maximize2, Minimize2, PanelBottom, PanelRight, Pencil, Play, StickyNote, X,
} from "lucide-react";
import type { RunView } from "../../runs";
import { fmtMs, fmtTime, hookKey, latestHooks } from "../../runs";
import type { ResultsData } from "../../results/compute";
import { recentOutcomes } from "../../results/history";
import type { CaseInfo } from "../../results/model";
import { LABELS, failureText, labelName, shortReason, stateLabel, stepFailure, stepTitle } from "../../results/model";
import { runAgainInPlace } from "../../results/rerun";
import { openCase, openFolder } from "../../nav";
import { useStore } from "../../store";
import type { Phase } from "../../suiteOps";
import { find } from "../../suiteOps";
import type { CaseResult, HookResult, StepRef, StepResult } from "../../types";
import { Button, DropMenu, HttpCode, StatusIcon } from "../ui";
import { StepTabs, defaultTab } from "./StepTabs";
import type { StepTab } from "./StepTabs";

const PHASE_ORDER: Record<string, number> = { setup: 0, pre: 1, step: 2, cleanup: 3, teardown: 4 };

export function stepKey(s: { phase: string; index: number }): string {
  return `${s.phase}:${s.index}`;
}

function defaultStepKey(steps?: StepResult[]): string | undefined {
  if (!steps?.length) return undefined;
  const bad = steps.find((s) => s.status !== "pass" && s.status !== "skipped");
  return stepKey(bad ?? steps[0]);
}

type Item = { kind: "step"; s: StepResult } | { kind: "off"; s: StepRef };

function items(steps?: StepResult[], off?: StepRef[]): Item[] {
  const list: Item[] = [...(steps ?? []).map((s) => ({ kind: "step" as const, s })), ...(off ?? []).map((s) => ({ kind: "off" as const, s }))];
  return list.sort((a, b) => (PHASE_ORDER[a.s.phase] ?? 9) - (PHASE_ORDER[b.s.phase] ?? 9) || a.s.index - b.s.index);
}

export function DetailPanel({ data }: { data: ResultsData }) {
  const runSel = useStore((s) => s.runSel);
  const run = data.run!;
  if (runSel.hook) {
    const h = latestHooks(run).find((x) => hookKey(x) === runSel.hook);
    if (h) return <HookDetail run={run} hook={h} data={data} />;
  }
  const info = runSel.caseId ? data.infos.find((i) => i.id === runSel.caseId) : undefined;
  if (!info) {
    return (
      <div className="detail empty-detail">
        <span className="muted">This test case is not part of this run.</span>
        <Button size="sm" variant="ghost" icon={<X size={14} />} onClick={() => useStore.getState().selectRun({})}>Close</Button>
      </div>
    );
  }
  return <CaseDetail key={info.id} run={run} info={info} data={data} />;
}

// ---------------------------------------------------------------- header pieces

function PanelButtons() {
  const maximized = useStore((s) => s.maximized);
  const dock = useStore((s) => s.dock);
  const st = useStore.getState();
  return (
    <span className="row" style={{ gap: 0, flex: "none" }}>
      <Button variant="ghost" icon={maximized ? <Minimize2 size={15} /> : <Maximize2 size={15} />} title={maximized ? "Show the list again (M)" : "Give the details the whole screen (M)"}
        onClick={() => st.setMaximized(!maximized)} />
      <Button variant="ghost" icon={dock === "bottom" ? <PanelRight size={15} /> : <PanelBottom size={15} />}
        title={dock === "bottom" ? "Show the details on the right" : "Show the details below the list"} onClick={() => st.setDock(dock === "bottom" ? "right" : "bottom")} />
      <Button variant="ghost" icon={<X size={15} />} title="Close the details (Esc)" onClick={() => { st.selectRun({}); st.setMaximized(false); }} />
    </span>
  );
}

function Nav({ data, id }: { data: ResultsData; id: string }) {
  const st = useStore.getState();
  const i = data.order.indexOf(id);
  const go = (n: number) => {
    const next = data.order[n];
    if (next) st.selectRun({ caseId: next, tab: st.runSel.tab });
  };
  if (!data.order.length) return null;
  return (
    <span className="nav">
      <button className="btn ghost sm icon" disabled={i <= 0} onClick={() => go(i - 1)} title="Previous test case (K)"><ChevronLeft size={15} /></button>
      <span>{i >= 0 ? <><b>{i + 1}</b> of {data.order.length}</> : <>{data.order.length} in the list</>}</span>
      <button className="btn ghost sm icon" disabled={i >= data.order.length - 1} onClick={() => go(i < 0 ? 0 : i + 1)} title="Next test case (J)"><ChevronRight size={15} /></button>
    </span>
  );
}

function RecentRuns({ run, data, caseId }: { run: RunView; data: ResultsData; caseId: string }) {
  const list = recentOutcomes(run, data.past, caseId);
  if (list.length < 2) return null;
  return (
    <span className="recentruns" title="This test case in the latest runs, oldest first">
      <span className="faint">Last {list.length} runs</span>
      {list.map((o, i) => (
        <i key={o.runId} className={`rd ${o.status ?? "none"}${i === list.length - 1 ? " now" : ""}`}
          title={`${fmtTime(o.startedAt)}: ${o.status ?? "not in that run"}`} />
      ))}
    </span>
  );
}

function Labels({ run, c }: { run: RunView; c?: CaseResult }) {
  const st = useStore.getState();
  if (!c) return null;
  const cur = labelName(c.triage?.label);
  return (
    <span className="labels">
      {LABELS.map((l) => (
        <button key={l.id} className={`lab ${cur === l.label ? l.label.toLowerCase() : "ghost"}`} title={`${l.hint} (${l.key})`}
          onClick={() => st.setTriage(run.runId, [c.caseId], { label: cur === l.label ? null : l.id })}>{l.label}</button>
      ))}
    </span>
  );
}

function Note({ run, c }: { run: RunView; c?: CaseResult }) {
  const [text, setText] = useState(c?.triage?.note ?? "");
  const timer = useRef<number>();
  useEffect(() => setText(c?.triage?.note ?? ""), [c?.caseId, c?.triage?.note]);
  if (!c) return null;
  return (
    <label className="note-in">
      <StickyNote size={13} />
      <input value={text} placeholder="Add a note (ticket, cause…)" spellCheck={false}
        onChange={(e) => {
          const v = e.target.value;
          setText(v);
          window.clearTimeout(timer.current);
          timer.current = window.setTimeout(() => useStore.getState().setTriage(run.runId, [c.caseId], { note: v }), 400);
        }} />
    </label>
  );
}

// ---------------------------------------------------------------- test case

function CaseDetail({ run, info, data }: { run: RunView; info: CaseInfo; data: ResultsData }) {
  const runSel = useStore((s) => s.runSel);
  const suite = useStore((s) => s.suite);
  const st = useStore.getState();
  const attempts = useMemo(() => [...(run.history[info.id] ?? []), ...(run.cases[info.id] ? [run.cases[info.id]] : [])], [run, info.id]);
  const which = runSel.attempt && runSel.attempt <= attempts.length ? runSel.attempt : attempts.length;
  const result = attempts[which - 1];
  const latest = which === attempts.length;
  const list = useMemo(() => items(result?.steps, result?.disabledSteps), [result]);
  const selKey = runSel.step && list.some((x) => x.kind === "step" && stepKey(x.s) === runSel.step) ? runSel.step : defaultStepKey(result?.steps);
  const step = result?.steps?.find((s) => stepKey(s) === selKey);
  const inSuite = useMemo(() => !!suite && find(suite, info.id)?.node.type === "case", [suite, info.id]);
  const busy = !!run.running[info.id] || !!run.pending?.[info.id];
  const setups = useMemo(() => latestHooks(run).filter((h) => h.hook === "setup" && (h.path ?? []).every((x, k) => info.path[k] === x)
    && (h.path ?? []).length <= info.path.length).sort((a, b) => (a.path ?? []).length - (b.path ?? []).length), [run, info.path]);

  const edit = () => {
    useStore.setState({ fromResults: { runId: run.runId, caseId: info.id, label: info.key } });
    if (step) openCase(info.id, step.phase as Phase, step.index);
    else openCase(info.id);
  };
  const again = (reuseSetup: boolean) => runAgainInPlace(run, [info.id], { reuseSetup });
  const reusable = latestHooks(run).some((h) => h.hook === "setup" && h.vars && Object.keys(h.vars).length);

  return (
    <div className="detail">
      <div className="dh">
        <StatusIcon status={busy ? (run.running[info.id] ? "running" : "queued") : info.state === "disabled" ? "disabled" : result?.status ?? info.state} size={18} />
        <span className="dkey">{info.key}</span>
        <span className="dname" title={info.name}>{info.name}</span>
        {attempts.length > 1 ? (
          <DropMenu trigger={({ toggle }) => (
            <button className="attempt" onClick={toggle} title="This test case ran more than once in this run">
              {latest ? `Latest of ${attempts.length} runs` : `Run ${which} of ${attempts.length}`}<ChevronDown size={12} />
            </button>
          )} items={attempts.map((a, i) => ({
            label: `Run ${i + 1}: ${a.status}${a.startedAt ? ` at ${fmtTime(a.startedAt)}` : ""}${i === attempts.length - 1 ? " (latest)" : ""}`,
            onClick: () => st.selectRun({ ...st.runSel, attempt: i === attempts.length - 1 ? undefined : i + 1, step: undefined }),
          })).reverse()} />
        ) : null}
        <span className="grow" />
        <Nav data={data} id={info.id} />
        <Button icon={<Pencil size={14} />} onClick={edit} disabled={!inSuite} title={inSuite ? "Open this test case in the editor (E)" : "This test case is not in the open suite"}>Edit</Button>
        <span className="split-btn">
          <Button variant="run" icon={busy ? <LoaderCircle size={14} className="spin" /> : <Play size={14} />} disabled={!inSuite || busy || !run.done}
            onClick={() => again(false)} title="Run this test case again; the result is added to this run (R)">{info.state === "disabled" ? "Run anyway" : "Run again"}</Button>
          <DropMenu align="right" trigger={({ toggle }) => <Button variant="run" className="split-r" icon={<ChevronDown size={14} />} disabled={!inSuite || busy || !run.done} onClick={toggle} title="More ways to run" />}
            items={[
              { label: "Run again with its folder setup", icon: <Play size={14} />, onClick: () => again(false) },
              { label: "Run again reusing this run's setup values", icon: <Play size={14} />, onClick: () => again(true), disabled: !reusable },
            ]} />
        </span>
        <PanelButtons />
      </div>
      <div className="dm">
        <span className="crumb" title={info.path.join(" / ")}>{info.path.length ? info.path.join(" › ") : "Top level"}</span>
        {result ? (
          <span className="facts">
            <b className={`st-${result.status}`}>{stateLabel(result.status)}</b>
            <span>{fmtMs(result.durationMs)}</span>
            {result.startedAt ? <span>{fmtTime(result.startedAt)}</span> : null}
            {result.cleanupFailed ? <span className="badge warn" title="Test data may be left behind">cleanup failed</span> : null}
          </span>
        ) : null}
        <RecentRuns run={run} data={data} caseId={info.id} />
        <span className="grow" />
        {latest ? <Labels run={run} c={result} /> : null}
        {latest ? <Note run={run} c={result} /> : null}
      </div>
      {busy ? <div className="dbanner run"><LoaderCircle size={14} className="spin" />{run.running[info.id] ? "Running again…" : "Waiting to run again…"} The result will be added to this run.</div> : null}
      {!latest ? <div className="dbanner"><span>You are looking at run {which} of {attempts.length}.</span><button className="link" onClick={() => st.selectRun({ ...st.runSel, attempt: undefined, step: undefined })}>Show the latest</button></div> : null}
      {info.state === "disabled" && !result ? (
        <div className="dempty">
          <CircleMinus size={22} className="faint" />
          <div>{info.disabled?.folder ? <>The folder <b>{info.disabled.folder}</b> is disabled, so this test case did not run.</> : "This test case is disabled in the suite, so it did not run."}</div>
          <div className="faint">Edit opens it in the editor, where it can be enabled. Run anyway runs it once and adds the result here.</div>
        </div>
      ) : !result ? (
        <div className="dempty">{run.running[info.id] ? <><LoaderCircle size={20} className="spin" />Running…</> : "Waiting for its turn."}</div>
      ) : (
        <>
          {result.status !== "pass" && result.reason ? <Reason result={result} /> : null}
          <div className="db">
            <div className="dsteps">
              {setups.map((h) => (
                <button key={hookKey(h)} className="ds ctx" onClick={() => st.selectRun({ hook: hookKey(h) })} title="Open this setup">
                  <ListStart size={15} className={`st-${h.status}`} />
                  <span className="nm"><span className="ph">{h.path?.length ? "Folder setup" : "Suite setup"}</span>{h.path?.length ? h.path[h.path.length - 1] : "Suite"}</span>
                  <span className="rt">{setupSummary(h)}</span>
                </button>
              ))}
              <StepList list={list} selected={selKey} onSelect={(k) => st.selectRun({ ...st.runSel, step: k })} />
            </div>
            <div className="dtabs-wrap">
              {step ? (
                <StepTabs key={selKey} step={step} tab={runSel.tab ?? defaultTab(step)} onTab={(t: StepTab) => st.selectRun({ ...useStore.getState().runSel, tab: t })}
                  context={{ run, result }} fileBase={`${info.key}-${selKey?.replace(":", "")}`} onRunAgain={inSuite && run.done && !busy ? () => again(false) : undefined} />
              ) : <div className="pad muted">{result.steps?.length ? "Pick a step." : "No step ran."}</div>}
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function setupSummary(h: HookResult): string {
  const values = Object.keys(Object.values(h.vars ?? {})[0] ?? {}).length;
  if (values) return `${values} value${values === 1 ? "" : "s"} saved`;
  const steps = h.steps?.length ?? 0;
  return `${steps} step${steps === 1 ? "" : "s"}`;
}

function Reason({ result }: { result: CaseResult }) {
  const s = result.steps?.find((x) => x.status !== "pass" && x.status !== "skipped");
  const f = s ? stepFailure(s) : undefined;
  return (
    <div className={`dreason ${result.status}`}>
      {f?.at ? <span className="at">{stepTitle(f.at)} “{f.at.name}”</span> : null}
      <span className="why" title={result.reason}>{f ? failureText(f) : shortReason(result.reason ?? "")}</span>
    </div>
  );
}

function StepList({ list, selected, onSelect }: { list: Item[]; selected?: string; onSelect: (k: string) => void }) {
  return (
    <>
      {list.map((x) => {
        if (x.kind === "off") {
          return (
            <div key={"off" + stepKey(x.s)} className="ds off" title="Disabled: this step did not run">
              <CircleMinus size={15} className="st-skipped" />
              <span className="nm"><span className="ph">{stepTitle(x.s)} · disabled</span>{x.s.name}</span>
            </div>
          );
        }
        const s = x.s;
        const k = stepKey(s);
        return (
          <button key={k} className={`ds${k === selected ? " on" : ""}`} onClick={() => onSelect(k)}>
            <StatusIcon status={s.status} size={15} />
            <span className="nm"><span className="ph">{stepTitle(s)}{s.attempts && s.attempts > 1 ? ` · tried ${s.attempts}×` : ""}</span>{s.name}</span>
            <span className="rt"><HttpCode code={s.target?.status} /><span>{fmtMs(s.durationMs)}</span></span>
          </button>
        );
      })}
    </>
  );
}

// ---------------------------------------------------------------- setup / teardown

function HookDetail({ run, hook, data }: { run: RunView; hook: HookResult; data: ResultsData }) {
  const runSel = useStore((s) => s.runSel);
  const st = useStore.getState();
  const list = useMemo(() => items(hook.steps, hook.disabledSteps), [hook]);
  const selKey = runSel.step && list.some((x) => x.kind === "step" && stepKey(x.s) === runSel.step) ? runSel.step : defaultStepKey(hook.steps);
  const step = hook.steps?.find((s) => stepKey(s) === selKey);
  const where = hook.path?.length ? hook.path.join(" › ") : "Suite";
  const blocked = useMemo(() => hook.hook === "setup" && hook.status !== "pass"
    ? data.infos.filter((i) => i.state === "blocked" && (hook.path ?? []).every((x, k) => i.path[k] === x) && /^Setup of /.test(i.result?.reason ?? "")).map((i) => i.id)
    : [], [hook, data.infos]);
  const edit = () => {
    useStore.setState({ fromResults: { runId: run.runId, label: `${where} ${hook.hook}` } });
    const idx = step?.index ?? 0;
    if (hook.folderId) openFolder(hook.folderId, hook.hook as Phase, idx);
    else st.select({ kind: "suite", tab: "hooks", phase: hook.hook as Phase, step: idx });
  };
  return (
    <div className="detail">
      <div className="dh">
        {hook.hook === "setup" ? <ListStart size={18} className={`st-${hook.status}`} /> : <ListEnd size={18} className={`st-${hook.status}`} />}
        <span className="dkey">{hook.hook === "setup" ? "Setup" : "Teardown"}</span>
        <span className="dname" title={where}>{where}</span>
        <span className="grow" />
        {blocked.length ? (
          <Button variant="run" icon={<Play size={14} />} disabled={!run.done || !!run.rerun} onClick={() => runAgainInPlace(run, blocked)}
            title="Runs the setup again, then the test cases it blocked; the results are added to this run">Run the {blocked.length} blocked again</Button>
        ) : null}
        <Button icon={<Pencil size={14} />} onClick={edit} title="Open this setup or teardown in the editor">Edit</Button>
        <PanelButtons />
      </div>
      <div className="dm">
        <span className="facts">
          <b className={`st-${hook.status}`}>{stateLabel(hook.status)}</b>
          <span>{fmtMs(hook.durationMs)}</span>
          {hook.startedAt ? <span>{fmtTime(hook.startedAt)}</span> : null}
          {hook.attempt && hook.attempt > 1 ? <span className="badge">ran {hook.attempt} times</span> : null}
        </span>
        {blocked.length ? <span className="faint">blocked {blocked.length} test case{blocked.length > 1 ? "s" : ""}</span> : null}
      </div>
      {hook.status !== "pass" && hook.reason ? <div className={`dreason ${hook.status}`}><span className="why" title={hook.reason}>{hook.reason}</span></div> : null}
      <div className="db">
        <div className="dsteps"><StepList list={list} selected={selKey} onSelect={(k) => st.selectRun({ ...st.runSel, step: k })} /></div>
        <div className="dtabs-wrap">
          {step ? (
            <StepTabs key={selKey} step={step} tab={runSel.tab ?? defaultTab(step)} onTab={(t: StepTab) => st.selectRun({ ...useStore.getState().runSel, tab: t })}
              context={{ run, hook }} fileBase={`${hook.hook}-${selKey?.replace(":", "")}`} />
          ) : <div className="pad muted">No step ran.</div>}
        </div>
      </div>
    </div>
  );
}
