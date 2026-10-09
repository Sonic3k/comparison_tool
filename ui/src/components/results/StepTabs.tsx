import { useMemo, useState } from "react";
import type { ReactNode } from "react";
import { CircleCheck, CircleX, Copy, CornerDownRight, Layers, ListStart, Lock, Search, Sparkles, SquareTerminal, TriangleAlert, Variable } from "lucide-react";
import type { RunView } from "../../runs";
import { latestHooks } from "../../runs";
import { PHASE_NAMES, toCurl } from "../../results/model";
import { useStore } from "../../store";
import type { CaseResult, Exchange, HookResult, KeyValue, StepResult, VarSource, VarUse } from "../../types";
import { pretty } from "../JsonView";
import type { JsonActions } from "../JsonView";
import { Button, HttpCode, Method, copyText } from "../ui";
import { Diffed, ResponseView } from "./ResponseView";
import type { Lane } from "./ResponseView";

/** What a step belongs to, for the Variables tab: the run, and the test case or hook it is part of. */
export interface StepContext {
  run?: RunView;
  result?: CaseResult;
  hook?: HookResult;
}

export type StepTab = "checks" | "response" | "request" | "variables" | "headers" | "curl";

function lanesOf(r: StepResult): Lane[] {
  const out: Lane[] = [];
  if (r.baseline) out.push({ name: "Baseline", ex: r.baseline });
  if (r.target) out.push({ name: r.baseline ? "Target" : "Response", ex: r.target });
  return out;
}

export function defaultTab(r: StepResult): StepTab {
  const body = r.target?.responseBody;
  if (body) return "response";
  if (r.status === "pass") return "request";
  return "checks";
}

/** The tabs of one step's result: checks, the response as received, the request as sent, variables, headers, cURL. */
export function StepTabs({ step, tab, onTab, context, actions, fileBase, onRunAgain }: {
  step: StepResult;
  tab?: string;
  onTab?: (t: StepTab) => void;
  context?: StepContext;
  actions?: JsonActions;
  fileBase?: string;
  onRunAgain?: () => void;
}) {
  const [own, setOwn] = useState<StepTab>(() => defaultTab(step));
  const current = (tab as StepTab) ?? own;
  const setTab = onTab ?? setOwn;
  const lanes = lanesOf(step);
  const checks = step.assertions ?? [];
  const failedChecks = checks.map((a, i) => ({ n: i + 1, a })).filter((x) => !x.a.pass);
  const vars = step.target?.vars ?? [];
  const items: { id: StepTab; label: string; badge?: ReactNode }[] = [
    {
      id: "checks", label: "Checks",
      badge: failedChecks.length || step.diffs?.length
        ? <span className="c bad">{failedChecks.length ? `${failedChecks.length} of ${checks.length} failed` : `${step.diffs!.length} diff`}</span>
        : step.status === "error" ? <span className="c warn">error</span> : checks.length ? <span className="c ok">{checks.length}</span> : null,
    },
    { id: "response", label: "Response" },
    { id: "request", label: "Request" },
    { id: "variables", label: "Variables", badge: vars.length ? <span className="c">{vars.length}</span> : null },
    { id: "headers", label: "Headers" },
    { id: "curl", label: "cURL" },
  ];
  return (
    <div className="step-tabs">
      <div className="dtabs" role="tablist">
        {items.map((t) => (
          <button key={t.id} role="tab" aria-selected={current === t.id} className={`dtab${current === t.id ? " on" : ""}`} onClick={() => setTab(t.id)}>
            {t.label}{t.badge}
          </button>
        ))}
        {step.attempts && step.attempts > 1 ? <span className="badge warn" style={{ marginLeft: "auto", alignSelf: "center" }}>tried {step.attempts} times</span> : null}
      </div>
      <div className="dtab-body">
        {current === "checks" ? <ChecksView step={step} /> : null}
        {current === "response" ? (
          lanes.length ? <ResponseView lanes={lanes} failed={failedChecks} diffs={step.diffs} actions={actions} fileBase={fileBase ?? "response"} onRunAgain={onRunAgain} />
            : <div className="pad muted">{step.reason ?? "No response was recorded for this step."}</div>
        ) : null}
        {current === "request" ? <Lanes lanes={lanes} render={(l) => <RequestView ex={l.ex} />} /> : null}
        {current === "variables" ? <VariablesView step={step} context={context} /> : null}
        {current === "headers" ? <Lanes lanes={lanes} render={(l) => <HeadersView ex={l.ex} />} /> : null}
        {current === "curl" ? <Lanes lanes={lanes} render={(l) => <CurlView ex={l.ex} />} /> : null}
      </div>
    </div>
  );
}

function Lanes({ lanes, render }: { lanes: Lane[]; render: (l: Lane) => ReactNode }) {
  if (!lanes.length) return <div className="pad muted">Nothing was sent for this step.</div>;
  if (lanes.length === 1) return <div className="pad scroll-y">{render(lanes[0])}</div>;
  return (
    <div className="pad scroll-y lanes2">
      {lanes.map((l) => (
        <div key={l.name} style={{ minWidth: 0 }}>
          <div className="lane-title">{l.name} <span className="faint">{l.ex.env}</span></div>
          {render(l)}
        </div>
      ))}
    </div>
  );
}

// ---------------------------------------------------------------- checks

function ChecksView({ step }: { step: StepResult }) {
  const checks = step.assertions ?? [];
  const failed = checks.filter((a) => !a.pass).length;
  const [onlyFailed, setOnlyFailed] = useState(failed > 0 && checks.length > 8);
  return (
    <div className="pad scroll-y">
      {step.status === "error" && step.reason ? <div className="note warn" style={{ marginBottom: 10 }}>{step.reason}</div> : null}
      {step.status === "skipped" && step.reason ? <div className="note" style={{ marginBottom: 10 }}>Did not run: {step.reason}</div> : null}
      {checks.length ? (
        <>
          <div className="row" style={{ marginBottom: 6 }}>
            <span className="muted" style={{ fontSize: 12.5 }}>{failed ? `${failed} of ${checks.length} checks failed` : `All ${checks.length} checks passed`}</span>
            {failed && checks.length > failed ? (
              <label className="check right" style={{ fontSize: 12.5 }}>
                <input type="checkbox" checked={onlyFailed} onChange={(e) => setOnlyFailed(e.target.checked)} />
                <span>Only failed</span>
              </label>
            ) : null}
          </div>
          <div className="checks">
            {checks.map((a, i) => {
              if (onlyFailed && a.pass) return null;
              const m = a.message ?? "";
              const k = m.lastIndexOf(", got ");
              const ea = m.startsWith("expected ") && k > 0 ? { e: m.slice(9, k), g: m.slice(k + 6) } : null;
              return (
                <div key={i} className={`ckrow${a.pass ? "" : " bad"}`}>
                  {a.pass ? <CircleCheck size={14} className="st-pass" /> : <CircleX size={14} className="st-fail" />}
                  <span className="cn">Check {i + 1}</span>
                  <div className="grow">
                    <div className="mono expr">{a.expr}</div>
                    {!a.pass && ea ? (
                      <div className="ea">
                        <span className="faint">got</span><Diffed text={ea.g} other={ea.e} />
                        <span className="faint">expected</span><Diffed text={ea.e} other={ea.g} />
                      </div>
                    ) : !a.pass && m ? <div className="msg">{m}</div> : null}
                  </div>
                </div>
              );
            })}
          </div>
        </>
      ) : step.status !== "error" ? <div className="muted">This step has no checks.</div> : null}
      {step.diffs?.length ? (
        <div style={{ marginTop: 14 }}>
          <div className="sub-title">Differences between baseline and target</div>
          {step.diffs.map((d, i) => <div key={i} className="diff-line">{d}</div>)}
        </div>
      ) : null}
      {step.missed?.length ? (
        <div className="note warn" style={{ marginTop: 12 }}>Not found in the response, so not saved: {step.missed.join(", ")}</div>
      ) : null}
    </div>
  );
}

// ---------------------------------------------------------------- request, headers, cURL

function RequestView({ ex }: { ex: Exchange }) {
  const query = useMemo(() => {
    try {
      return [...new URL(ex.url ?? "").searchParams.entries()];
    } catch {
      return [];
    }
  }, [ex.url]);
  return (
    <div className="col" style={{ gap: 12 }}>
      <div className="req-line"><Method m={ex.method} /><span className="mono">{ex.url}</span>
        <Button size="sm" variant="ghost" icon={<Copy size={13} />} title="Copy the URL" onClick={() => copy(ex.url ?? "", "URL copied")} />
      </div>
      {ex.error ? <div className="note warn">{ex.error}</div> : null}
      {query.length ? <Table title="Query" rows={query.map(([k, v]) => ({ key: k, value: v }))} /> : null}
      {ex.requestHeaders?.length ? <Table title="Headers" rows={ex.requestHeaders} /> : ex.requestHeaders ? null : <div className="faint">Headers and body were not kept for this run.</div>}
      {ex.requestBody ? (
        <div>
          <div className="sub-title row">Body
            <Button size="sm" variant="ghost" icon={<Copy size={13} />} onClick={() => copy(ex.requestBody ?? "", "Request body copied")}>Copy</Button>
          </div>
          <pre className="pre">{pretty(ex.requestBody)}</pre>
        </div>
      ) : null}
    </div>
  );
}

function HeadersView({ ex }: { ex: Exchange }) {
  if (!ex.responseHeaders?.length) return <div className="muted">{ex.responseHeaders ? "No headers." : "Headers were not kept for this run."}</div>;
  return (
    <div className="col" style={{ gap: 6 }}>
      <div className="row" style={{ gap: 8 }}><HttpCode code={ex.status} /><span className="faint">{ex.responseHeaders.length} response headers</span></div>
      <Table rows={ex.responseHeaders} />
    </div>
  );
}

let lastShell: "bash" | "cmd" = "bash";

function CurlView({ ex }: { ex: Exchange }) {
  const [shell, setShellState] = useState(lastShell);
  const setShell = (s: "bash" | "cmd") => {
    lastShell = s;
    setShellState(s);
  };
  if (!ex.url) return <div className="muted">Nothing was sent.</div>;
  if (ex.requestHeaders === undefined && ex.requestBody === undefined && ex.method !== "GET") {
    return <div className="muted">The request was not kept for this run, so it cannot be rebuilt here.</div>;
  }
  const text = toCurl(ex, shell);
  const masked = (ex.requestHeaders ?? []).some((h) => /\.\.\.|\*\*\*\*/.test(h.value) && /authorization|api-?key/i.test(h.key));
  return (
    <div className="col" style={{ gap: 8 }}>
      <div className="row">
        <div className="segmented sm">
          <button className={shell === "bash" ? "on" : ""} onClick={() => setShell("bash")}>Bash</button>
          <button className={shell === "cmd" ? "on" : ""} onClick={() => setShell("cmd")}>Windows CMD</button>
        </div>
        <Button size="sm" icon={<SquareTerminal size={13} />} onClick={() => copy(text, "cURL command copied")}>Copy command</Button>
      </div>
      {masked ? <div className="note">Basic credentials and API keys are hidden in results; put the real value in before running it.</div> : null}
      <pre className="pre">{text}</pre>
    </div>
  );
}

function Table({ title, rows }: { title?: string; rows: KeyValue[] }) {
  return (
    <div>
      {title ? <div className="sub-title">{title}</div> : null}
      <table className="kvt">
        <tbody>
          {rows.map((h, i) => (
            <tr key={i}>
              <td className="k">{h.key}</td>
              <td className="v">{h.value}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

async function copy(text: string, done: string) {
  await copyText(text);
  useStore.getState().toast("ok", done);
}

// ---------------------------------------------------------------- variables

function VariablesView({ step, context }: { step: StepResult; context?: StepContext }) {
  const laneNames = [step.target ? "target" : null, step.baseline ? "baseline" : null].filter(Boolean) as string[];
  const [lane, setLane] = useState(laneNames[0] ?? "target");
  const [view, setView] = useState<"used" | "start">("used");
  const [filter, setFilter] = useState("");
  const ex = lane === "baseline" ? step.baseline : step.target;
  const vars = ex?.vars ?? [];
  const startGroups = useMemo(() => valuesAtStart(step, context, lane), [step, context, lane]);
  const startCount = startGroups.reduce((n, g) => n + g.rows.length, 0);
  const f = filter.trim().toLowerCase();
  const pick = (name: string, value?: string | null) => !f || name.toLowerCase().includes(f) || (value ?? "").toLowerCase().includes(f);
  const failedChecks = new Set((step.assertions ?? []).map((a, i) => (a.pass ? -1 : i + 1)).filter((n) => n > 0));
  const caseKey = context?.result?.key;
  return (
    <div className="vars">
      <div className="vars-tools">
        <div className="segmented sm">
          <button className={view === "used" ? "on" : ""} onClick={() => setView("used")}>Used by this step <b>{vars.length}</b></button>
          {context?.run ? (
            <button className={view === "start" ? "on" : ""} onClick={() => setView("start")} title="Every value the test case could use when it started">
              All values at the start <b>{startCount}</b>
            </button>
          ) : null}
        </div>
        {laneNames.length > 1 ? (
          <div className="segmented sm">
            {laneNames.map((l) => <button key={l} className={lane === l ? "on" : ""} onClick={() => setLane(l)}>{l === "target" ? "Target" : "Baseline"}</button>)}
          </div>
        ) : null}
        <span className="grow" />
        <span className="faint row" style={{ gap: 4, fontSize: 12 }}><Lock size={12} />Passwords and secrets are hidden</span>
        <div className="find sm">
          <Search size={13} />
          <input placeholder="Filter" value={filter} onChange={(e) => setFilter(e.target.value)} spellCheck={false} />
        </div>
      </div>
      <div className="vars-body">
        {view === "used" ? (
          vars.length ? (
            <table className="vt">
              <colgroup><col style={{ width: "18%" }} /><col style={{ width: "38%" }} /><col /><col style={{ width: "17%" }} /></colgroup>
              <thead><tr><th>Name</th><th>Value when sent</th><th>Set by</th><th>Used in</th></tr></thead>
              <tbody>
                {vars.filter((v) => pick(v.name, v.value)).map((v) => (
                  <VarRow key={v.name} v={v} caseKey={caseKey} failedChecks={failedChecks} checks={step.assertions ?? []} />
                ))}
              </tbody>
            </table>
          ) : (
            <div className="pad muted">
              {ex?.vars === undefined && ex?.url ? "This result was saved by an earlier version of the tool, which did not record variables." : "This step used no variables."}
            </div>
          )
        ) : (
          <div>
            {startGroups.map((g) => {
              const rows = g.rows.filter(([k, v]) => pick(k, v));
              if (!rows.length) return null;
              return (
                <div key={g.title} className="vgroup">
                  <div className="vgroup-title">{g.icon}{g.title}<span className="faint">{rows.length}</span></div>
                  <table className="vt plain">
                    <colgroup><col style={{ width: "24%" }} /><col /></colgroup>
                    <tbody>
                      {rows.map(([k, v]) => (
                        <tr key={k}><td><span className="vname">{k}</span></td><td><Value v={v} /></td></tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              );
            })}
            {!startCount ? <div className="pad muted">No values were recorded for the start of this test case.</div> : null}
          </div>
        )}
        {view === "used" && step.extracted && Object.keys(step.extracted).length ? (
          <div className="vgroup">
            <div className="vgroup-title"><Sparkles size={13} />Saved by this step<span className="faint">{Object.keys(step.extracted).length}</span></div>
            <table className="vt plain">
              <colgroup><col style={{ width: "24%" }} /><col /></colgroup>
              <tbody>
                {Object.entries(step.extracted).filter(([k, v]) => pick(k, v)).map(([k, v]) => (
                  <tr key={k}><td><span className="vname">{k}</span></td><td><Value v={v} /></td></tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
        {view === "used" && step.missed?.length ? (
          <div className="pad"><div className="note warn">Not found in the response, so not saved: {step.missed.join(", ")}</div></div>
        ) : null}
      </div>
    </div>
  );
}

function VarRow({ v, caseKey, failedChecks, checks }: { v: VarUse; caseKey?: string; failedChecks: Set<number>; checks: { expr: string }[] }) {
  const unset = v.value === null || v.value === undefined;
  return (
    <tr className={unset ? "unset" : undefined}>
      <td><span className="vname">{v.name}</span></td>
      <td>{unset ? <span className="st-error">not set</span> : <Value v={v.value!} />}</td>
      <td><SetBy from={v.from} caseKey={caseKey} /></td>
      <td>
        <span className="uses">
          {(v.usedIn ?? []).map((u) => {
            const n = /^Check (\d+)$/.exec(u);
            const bad = n && failedChecks.has(Number(n[1]));
            return <span key={u} className={`use${bad ? " bad" : ""}`} title={n ? checks[Number(n[1]) - 1]?.expr : undefined}>{bad ? <CircleX size={11} /> : null}{u}</span>;
          })}
        </span>
      </td>
    </tr>
  );
}

function Value({ v }: { v: string }) {
  const [open, setOpen] = useState(false);
  const long = v.length > 80;
  return (
    <span className={`vval${open ? " open" : ""}`}>
      <span className="t" title={long && !open ? "Click to see all of it" : undefined} onClick={() => long && setOpen(!open)}>{v === "" ? <span className="faint">(empty)</span> : v}</span>
      <button className="cp" title="Copy" onClick={() => copy(v, "Value copied")}><Copy size={12} /></button>
    </span>
  );
}

function SetBy({ from, caseKey }: { from?: VarSource; caseKey?: string }) {
  if (!from) return <span className="faint">—</span>;
  const step = from.step ? <> · {PHASE_NAMES[from.kind] ?? from.kind} {from.step} <b>“{from.name}”</b></> : null;
  switch (from.kind) {
    case "env": return <span className="setby"><Layers size={12} />Environment <b>{from.owner}</b></span>;
    case "suite": return <span className="setby"><Variable size={12} />Suite variable</span>;
    case "override": return <span className="setby"><Variable size={12} />Value given for this run</span>;
    case "send": return <span className="setby"><Variable size={12} />Values for Send</span>;
    case "builtin": return <span className="setby"><Sparkles size={12} />Built-in, new each time</span>;
    case "reused": return <span className="setby"><ListStart size={12} />Setup values reused · {last(from.owner)}</span>;
    case "unset": return <span className="setby st-error"><TriangleAlert size={12} />Nothing set it</span>;
    case "setup":
    case "teardown":
      return <span className="setby" title={from.owner}><ListStart size={12} />{from.owner === "Suite" ? "Suite" : last(from.owner)} {from.kind}{from.step ? <> step {from.step} <b>“{from.name}”</b></> : null}</span>;
    case "pre":
    case "step":
    case "cleanup":
      return <span className="setby"><CornerDownRight size={12} />{from.owner === caseKey ? "This test case" : `Test case ${from.owner}`}{step}</span>;
    default:
      return <span className="setby">{from.owner ?? from.kind}</span>;
  }
}

function last(path?: string): string {
  if (!path) return "";
  const parts = path.split(" / ");
  return parts[parts.length - 1];
}

/** The values a test case could use when it started: environment and suite values, setups above it, earlier steps. */
function valuesAtStart(step: StepResult, ctx: StepContext | undefined, lane: string): { title: string; icon: ReactNode; rows: [string, string][] }[] {
  const run = ctx?.run;
  if (!run) return [];
  const out: { title: string; icon: ReactNode; rows: [string, string][] }[] = [];
  const startVars = run.header.variables?.[lane];
  if (startVars) out.push({ title: `Environment ${lane === "baseline" ? run.header.baselineEnv : run.header.env} and suite`, icon: <Layers size={13} />, rows: Object.entries(startVars) });
  const path = ctx?.result?.path ?? ctx?.hook?.path ?? [];
  const hooks = latestHooks(run).filter((h) => h.hook === "setup" && h.vars);
  const own = ctx?.hook;
  const chain = hooks.filter((h) => {
    const p = h.path ?? [];
    if (own && own.hook === "setup" && (own.folderId ?? "") === (h.folderId ?? "")) return false;
    return p.length <= path.length && p.every((x, i) => path[i] === x) && (!own || p.length < (own.path ?? []).length || own.hook === "teardown");
  }).sort((a, b) => (a.path ?? []).length - (b.path ?? []).length);
  for (const h of chain) {
    const v = h.vars?.[lane];
    if (v && Object.keys(v).length) out.push({ title: `${h.path?.length ? h.path[h.path.length - 1] : "Suite"} setup`, icon: <ListStart size={13} />, rows: Object.entries(v) });
  }
  const steps = ctx?.result?.steps ?? ctx?.hook?.steps ?? [];
  const before: [string, string][] = [];
  for (const s of steps) {
    if (s === step) break;
    for (const [k, v] of Object.entries(s.extracted ?? {})) before.push([k, v]);
  }
  if (before.length) out.push({ title: "Saved by earlier steps", icon: <Sparkles size={13} />, rows: before });
  return out;
}
