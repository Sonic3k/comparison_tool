import { useEffect, useMemo, useState } from "react";
import { ClipboardPaste, Copy, CopyPlus, Ellipsis, LoaderCircle, Send, Terminal, Trash2, X } from "lucide-react";
import { api } from "../api";
import { usePrefNumber, useSyncedText } from "../hooks";
import { knownValues, useStore } from "../store";
import type { InheritedDefaults, Phase } from "../suiteOps";
import { bodyToText, jsonTextToBody, namesInStep, stepKey } from "../suiteOps";
import type { Body, Extract, Issue, KeyValue, Step, StepOptions } from "../types";
import { CodeEditor } from "./CodeEditor";
import { CurlImport } from "./Dialogs";
import { KeyValueTable } from "./KeyValueTable";
import { StepResultView } from "./ResultViews";
import { Button, Check, DropMenu, Field, NumberInput, Splitter, TagInput, Tabs, copyText, useOverlay } from "./ui";

export interface StepContext {
  /** Test case or folder id; undefined for suite setup and teardown. */
  nodeId?: string;
  /** Folder whose defaults apply when the step is sent on its own. */
  folderId?: string;
  phase: Phase;
  index: number;
  inherited: InheritedDefaults[];
}

const METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"];
let lastTab = "params";

export function StepEditor({ step, onChange, onDelete, onDuplicate, ctx }: {
  step: Step;
  onChange: (s: Step, coalesce?: string) => void;
  onDelete?: () => void;
  onDuplicate?: () => void;
  ctx: StepContext;
}) {
  const suite = useStore((s) => s.suite)!;
  const env = useStore((s) => s.env);
  const baselineEnv = useStore((s) => s.baselineEnv);
  const issues = useStore((s) => s.issues);
  const key = stepKey(ctx.nodeId, ctx.phase, ctx.index);
  const result = useStore((s) => s.tryResults[key]);
  const [tab, setTabState] = useState(() => initialTab(step));
  const [sending, setSending] = useState(false);
  const [respH, setRespH] = usePrefNumber("respHeight", 320);
  const setTab = (t: string) => {
    lastTab = t;
    setTabState(t);
  };
  const ck = (field: string) => `${key}:${field}`;
  const patch = (p: Partial<Step>, field: string) => onChange(cleanStep({ ...step, ...p }), ck(field));

  const stepIssues = issues.filter((i: Issue) => (i.nodeId ?? undefined) === ctx.nodeId && i.section === ctx.phase && i.step === ctx.index);

  const send = async () => {
    if (sending) return;
    const st = useStore.getState();
    if (!st.suite) return;
    setSending(true);
    try {
      const known = knownValues(st.runs);
      const vars: Record<string, string> = {};
      for (const n of unknownNames(st.suite, st.env, st.baselineEnv, step)) {
        const v = st.tryVars[n] ?? known[n];
        if (v !== undefined && v !== "") vars[n] = v;
      }
      const r = await api.tryStep({ suite: st.suite, env: st.env, baselineEnv: st.baselineEnv, folderId: ctx.folderId, step, vars });
      st.setTryResult(key, r);
    } catch (e) {
      st.toast("error", (e as Error).message);
    } finally {
      setSending(false);
    }
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === "Enter" && !e.defaultPrevented) {
        e.preventDefault();
        send();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  const addAssertion = (line: string) => {
    const list = step.expect?.assertions ?? [];
    if (list.includes(line)) return useStore.getState().toast("info", "That check is already there");
    onChange(cleanStep({ ...step, expect: { ...step.expect, assertions: [...list, line] } }));
    useStore.getState().toast("ok", `Check added: ${line}`);
  };
  const addExtract = (name: string, path: string) => {
    const list = step.extract ?? [];
    if (list.some((e) => e.path === path)) return useStore.getState().toast("info", "That value is already saved");
    let n = name;
    for (let i = 2; list.some((e) => e.name === n); i++) n = `${name}${i}`;
    onChange(cleanStep({ ...step, extract: [...list, { name: n, path }] }));
    useStore.getState().toast("ok", `Saved as {{${n}}}`);
  };

  const copyCurl = async () => {
    const st = useStore.getState();
    try {
      const res = await api.curl({ suite: st.suite!, env: st.env, baselineEnv: st.baselineEnv, folderId: ctx.folderId, step, vars: st.tryVars });
      const target = res.find((r) => r.lane === "target") ?? res[0];
      if (!target) return;
      await copyText(target.curl);
      st.toast("ok", target.error ? `cURL copied (note: ${target.error})` : "cURL copied");
    } catch (e) {
      st.toast("error", (e as Error).message);
    }
  };

  const pasteCurl = () => {
    let id = 0;
    id = useOverlay.getState().showDialog(
      <CurlImport
        title="Replace the request with a cURL command"
        onClose={() => useOverlay.getState().closeDialog(id)}
        onStep={(parsed) => {
          onChange(cleanStep({ ...step, method: parsed.method, url: parsed.url, query: parsed.query, headers: parsed.headers, body: parsed.body }));
          useOverlay.getState().closeDialog(id);
        }}
      />,
    );
  };

  const queryCount = (step.query ?? []).filter((q) => !q.disabled).length;
  const headerCount = (step.headers ?? []).filter((q) => !q.disabled).length;
  const checks = (step.expect?.assertions?.length ?? 0) + (step.expect?.status ? 1 : 0) + (step.expect?.maxTimeMs ? 1 : 0);
  const optionsSet = !!step.options || !!step.notes;
  const tabs = [
    { id: "params", label: "Params", count: queryCount || undefined },
    { id: "headers", label: "Headers", count: headerCount || undefined },
    { id: "body", label: "Body", dot: step.body ? ("on" as const) : undefined },
    { id: "auth", label: "Auth", dot: step.auth ? ("on" as const) : undefined },
    { id: "expect", label: "Checks", count: checks || undefined, dot: stepIssues.some((i) => i.message.startsWith("Assertion")) ? ("bad" as const) : undefined },
    { id: "extract", label: "Save values", count: step.extract?.length || undefined },
    { id: "options", label: "Advanced", dot: optionsSet ? ("on" as const) : undefined },
  ];

  const envObj = suite.environments?.find((e) => e.name === env);
  const relative = step.url && !/^https?:\/\//i.test(step.url) && !step.url.startsWith("{{");

  return (
    <div className="step-editor" style={{ flex: 1 }}>
      <div className="step-top">
        <div className="row">
          <input className="input bare" style={{ fontWeight: 600, fontSize: 14 }} value={step.name} placeholder="Step name"
            onChange={(e) => patch({ name: e.target.value }, "name")} />
          <Check checked={!step.disabled} onChange={(v) => patch({ disabled: v ? undefined : true }, "disabled")} title="Disabled steps are skipped">Enabled</Check>
          <DropMenu align="right" trigger={({ toggle }) => <Button variant="ghost" icon={<Ellipsis size={16} />} title="More" onClick={toggle} />}
            items={[
              { label: "Copy as cURL", icon: <Terminal size={14} />, onClick: copyCurl },
              { label: "Replace with cURL…", icon: <ClipboardPaste size={14} />, onClick: pasteCurl },
              ...(onDuplicate ? [{ label: "Duplicate step", icon: <CopyPlus size={14} />, onClick: onDuplicate }] : []),
              ...(onDelete ? [{ sep: true as const }, { label: "Delete step", icon: <Trash2 size={14} />, danger: true, onClick: onDelete }] : []),
            ]} />
        </div>
        <div className="urlbar">
          <select className="select" value={step.method} onChange={(e) => patch({ method: e.target.value }, "method")}>
            {(METHODS.includes(step.method) ? METHODS : [step.method, ...METHODS]).map((m) => <option key={m}>{m}</option>)}
          </select>
          <input className="input" value={step.url} placeholder="/path or https://host/path, {{variables}} allowed" spellCheck={false}
            onChange={(e) => patch({ url: e.target.value }, "url")} />
          <Button variant="primary" icon={sending ? <LoaderCircle size={14} className="spin" /> : <Send size={14} />} onClick={send} disabled={sending}
            title="Send this request now (Ctrl+Enter)">Send</Button>
        </div>
        {relative ? (
          <div className="faint mono" style={{ fontSize: 11.5 }}>
            {envObj?.baseUrl ? <>→ {envObj.baseUrl.replace(/\/$/, "")}{step.url.startsWith("/") ? "" : "/"}{step.url}</> : <span className="st-error">Environment {env ?? ""} has no base URL; use a full URL</span>}
          </div>
        ) : null}
        {stepIssues.length ? (
          <div className="col" style={{ gap: 4 }}>
            {stepIssues.map((i, n) => <div key={n} className={`note ${i.level === "error" ? "bad" : "warn"}`}>{i.message}</div>)}
          </div>
        ) : null}
      </div>
      <div className="step-body">
        <Tabs items={tabs} value={tab} onChange={setTab} />
        <div className="step-tabs-panel" style={{ flex: 1 }}>
          {tab === "params" ? (
            <KeyValueTable rows={step.query} onChange={(q) => patch({ query: q }, "query")} keyPlaceholder="Query parameter" />
          ) : null}
          {tab === "headers" ? <HeadersTab step={step} ctx={ctx} onChange={(h) => patch({ headers: h }, "headers")} /> : null}
          {tab === "body" ? <BodyTab body={step.body} onChange={(b) => patch({ body: b }, "body")} /> : null}
          {tab === "auth" ? <AuthTab step={step} ctx={ctx} onChange={(a) => patch({ auth: a }, "auth")} /> : null}
          {tab === "expect" ? <ExpectTab step={step} onChange={(s) => onChange(cleanStep(s), ck("expect"))} /> : null}
          {tab === "extract" ? <ExtractTab step={step} isSetup={ctx.phase === "setup"} onChange={(x) => patch({ extract: x }, "extract")} /> : null}
          {tab === "options" ? <OptionsTab step={step} phase={ctx.phase} onChange={(s) => onChange(cleanStep(s), ck("options"))} /> : null}
        </div>
        <SendVars step={step} />
        {result ? (
          <>
            <Splitter axis="y" invert value={respH} min={120} max={Math.max(200, window.innerHeight - 220)} onChange={setRespH} />
            <div className="response" style={{ height: respH, flex: "none" }}>
              <div className="resp-head">
                <span className="section-title" style={{ margin: 0 }}>Last send</span>
                <span className="faint" style={{ fontSize: 12 }}>{env}{baselineEnv ? ` vs ${baselineEnv}` : ""}</span>
                <Button size="sm" variant="ghost" className="right" icon={<X size={13} />} title="Close" onClick={() => useStore.getState().setTryResult(key, undefined)} />
              </div>
              <div className="resp-body" style={{ padding: 0 }}>
                <StepResultView r={result} actions={{ onAssert: addAssertion, onExtract: addExtract }} />
              </div>
            </div>
          </>
        ) : null}
      </div>
    </div>
  );
}

function initialTab(step: Step): string {
  if (lastTab === "params" && !step.query?.length && step.body) return "body";
  if (lastTab === "body" && !step.body && step.method === "GET") return "params";
  return lastTab;
}

/** Drops empty parts so the suite file stays small. */
export function cleanStep(s: Step): Step {
  const out: Step = { ...s };
  if (!out.query?.length) delete out.query;
  if (!out.headers?.length) delete out.headers;
  if (!out.extract?.length) delete out.extract;
  if (!out.auth) delete out.auth;
  if (!out.notes) delete out.notes;
  if (!out.disabled) delete out.disabled;
  if (out.expect) {
    const e = { ...out.expect };
    if (!e.status) delete e.status;
    if (!e.assertions?.length) delete e.assertions;
    if (!e.maxTimeMs) delete e.maxTimeMs;
    out.expect = Object.keys(e).length ? e : undefined;
    if (!out.expect) delete out.expect;
  }
  if (out.options) {
    const o: StepOptions = { ...out.options };
    (Object.keys(o) as (keyof StepOptions)[]).forEach((k) => {
      const v = o[k];
      if (v === undefined || v === null || (Array.isArray(v) && !v.length)) delete o[k];
    });
    if (o.compare !== false) delete o.compare;
    if (!o.required) delete o.required;
    if (o.retry && !o.retry.times) delete o.retry;
    out.options = Object.keys(o).length ? o : undefined;
    if (!out.options) delete out.options;
  }
  if (out.body) {
    const b = out.body;
    const empty = b.type === "form" ? !b.form?.length : b.type === "json" ? b.json === undefined && !b.raw : !b.raw;
    if (empty) delete out.body;
  }
  return out;
}

function unknownNames(suite: { variables?: KeyValue[]; environments?: { name: string; variables?: KeyValue[] }[] }, env: string | undefined, baseline: string | undefined, step: Step): string[] {
  const known = new Set<string>(["baseUrl"]);
  (suite.variables ?? []).forEach((v) => known.add(v.key.replace(/^\{\{|}}$/g, "")));
  for (const e of suite.environments ?? []) {
    if (e.name === env || e.name === baseline) (e.variables ?? []).forEach((v) => known.add(v.key.replace(/^\{\{|}}$/g, "")));
  }
  return namesInStep(step).filter((n) => !known.has(n));
}

/** Values for the variables a step reads that come from earlier steps or setup, editable before sending. */
function SendVars({ step }: { step: Step }) {
  const suite = useStore((s) => s.suite)!;
  const env = useStore((s) => s.env);
  const baselineEnv = useStore((s) => s.baselineEnv);
  const tryVars = useStore((s) => s.tryVars);
  const runs = useStore((s) => s.runs);
  const names = useMemo(() => unknownNames(suite, env, baselineEnv, step), [suite, env, baselineEnv, step]);
  const known = useMemo(() => knownValues(runs), [runs]);
  if (!names.length) return null;
  return (
    <div style={{ borderTop: "1px solid var(--border)", padding: "8px 16px", background: "var(--surface-2)", flex: "none" }}>
      <div className="row wrap" style={{ gap: 8 }}>
        <span className="faint" style={{ fontSize: 12 }} title="Values from earlier steps or setup are not available when one step is sent on its own. Values seen in recent runs are filled in.">
          Values for Send
        </span>
        {names.map((n) => (
          <label key={n} className="row" style={{ gap: 4 }}>
            <span className="mono faint">{n}</span>
            <input className="input sm mono" style={{ width: 160 }} value={tryVars[n] ?? known[n] ?? ""} placeholder="value"
              onChange={(e) => useStore.getState().setTryVars({ ...useStore.getState().tryVars, [n]: e.target.value })} />
          </label>
        ))}
      </div>
    </div>
  );
}

function HeadersTab({ step, ctx, onChange }: { step: Step; ctx: StepContext; onChange: (h: KeyValue[]) => void }) {
  const suite = useStore((s) => s.suite)!;
  const env = useStore((s) => s.env);
  const envObj = suite.environments?.find((e) => e.name === env);
  const own = new Set((step.headers ?? []).filter((h) => !h.disabled).map((h) => h.key.toLowerCase()));
  const inherited: { from: string; h: KeyValue }[] = [];
  (envObj?.headers ?? []).filter((h) => !h.disabled).forEach((h) => inherited.push({ from: `Environment ${envObj!.name}`, h }));
  ctx.inherited.forEach((d) => (d.defaults.headers ?? []).filter((h) => !h.disabled).forEach((h) => inherited.push({ from: d.label, h })));
  return (
    <div>
      <KeyValueTable rows={step.headers} onChange={onChange} keyPlaceholder="Header" suggestions={HEADER_NAMES} />
      {inherited.length ? (
        <div className="inherited">
          <div className="field-label">Also sent (from defaults; a header set here wins)</div>
          {inherited.map((x, i) => (
            <div key={i} className="row" style={{ textDecoration: own.has(x.h.key.toLowerCase()) ? "line-through" : undefined }}>
              <span>{x.h.key}: {x.h.value}</span>
              <span className="right" style={{ fontFamily: "var(--font)" }}>{x.from}</span>
            </div>
          ))}
        </div>
      ) : null}
    </div>
  );
}

const HEADER_NAMES = ["Accept", "Accept-Language", "Authorization", "Cache-Control", "Content-Type", "Cookie", "If-Match", "If-None-Match", "Origin", "Referer", "User-Agent", "X-Request-Id", "X-Correlation-Id", "X-Api-Key"];

function BodyTab({ body, onChange }: { body?: Body; onChange: (b?: Body) => void }) {
  const type = body?.type ?? "none";
  const [text, setText] = useSyncedText(bodyToText(body));
  const valid = useMemo(() => {
    if (type !== "json" || !text.trim()) return true;
    try {
      JSON.parse(text);
      return true;
    } catch {
      return false;
    }
  }, [type, text]);
  const setType = (t: string) => {
    if (t === "none") return onChange(undefined);
    if (t === "form") return onChange({ type: "form", form: body?.form ?? [] });
    if (t === "text") return onChange({ type: "text", raw: bodyToText(body) });
    onChange(jsonTextToBody(bodyToText(body)) ?? { type: "json", raw: "" });
  };
  return (
    <div className="col" style={{ gap: 8 }}>
      <div className="row">
        <div className="segmented sm">
          {["none", "json", "form", "text"].map((t) => (
            <button key={t} className={type === t || (t === "none" && !body) ? "on" : ""} onClick={() => setType(t)}>
              {t === "none" ? "None" : t === "json" ? "JSON" : t === "form" ? "Form" : "Text"}
            </button>
          ))}
        </div>
        {type === "json" && text.trim() ? (
          valid ? <span className="faint" style={{ fontSize: 12 }}>Valid JSON</span>
            : <span className="st-error" style={{ fontSize: 12 }}>Not valid JSON on its own — kept as text (unquoted {"{{placeholders}}"} are fine)</span>
        ) : null}
        {type === "json" && valid && text.trim() ? (
          <Button size="sm" variant="ghost" className="right" onClick={() => {
            try {
              const pretty = JSON.stringify(JSON.parse(text), null, 2);
              setText(pretty, pretty);
            } catch {
              /* ignore */
            }
          }}>Format</Button>
        ) : null}
      </div>
      {type === "json" ? (
        <CodeEditor value={text} lang="json" minHeight={180} maxHeight={600} placeholder='{ "name": "{{name}}" }'
          onChange={(t) => {
            const b = jsonTextToBody(t);
            setText(t, bodyToText(b));
            onChange(b ?? { type: "json", raw: "" });
          }} />
      ) : null}
      {type === "text" ? (
        <CodeEditor value={body?.raw ?? ""} lang="text" minHeight={160} onChange={(t) => onChange({ type: "text", raw: t })} />
      ) : null}
      {type === "form" ? (
        <KeyValueTable rows={body?.form} onChange={(f) => onChange({ type: "form", form: f })} keyPlaceholder="Field" />
      ) : null}
      {type === "none" ? <div className="faint">No body is sent.</div> : null}
    </div>
  );
}

function AuthTab({ step, ctx, onChange }: { step: Step; ctx: StepContext; onChange: (a?: string) => void }) {
  const suite = useStore((s) => s.suite)!;
  const env = useStore((s) => s.env);
  const envObj = suite.environments?.find((e) => e.name === env);
  let inherited: { name?: string; from: string } = { name: envObj?.auth, from: envObj ? `environment ${envObj.name}` : "environment" };
  for (const d of ctx.inherited) if (d.defaults.auth) inherited = { name: d.defaults.auth, from: d.label.toLowerCase() };
  const profiles = suite.authProfiles ?? [];
  return (
    <div className="col" style={{ maxWidth: 520 }}>
      <Field label="Authentication for this step" hint={!step.auth ? `Currently: ${inherited.name ? `${inherited.name} (from ${inherited.from})` : "none"}` : undefined}>
        <select className="select" value={step.auth ?? ""} onChange={(e) => onChange(e.target.value || undefined)}>
          <option value="">Inherit</option>
          <option value="none">None (send without auth)</option>
          {profiles.map((p) => <option key={p.name} value={p.name}>{p.name} ({p.type})</option>)}
        </select>
      </Field>
      <div className="faint" style={{ fontSize: 12 }}>Profiles are defined in Suite settings → Auth. A token is fetched once per run and reused.</div>
    </div>
  );
}

const SYNTAX: [string, string][] = [
  ["$.id exists", "field is present"],
  ["$.status == \"ACTIVE\"", "equals (also !=)"],
  ["$.total > 0", "compare numbers (>, >=, <, <=)"],
  ["$.name contains \"abc\"", "text contains (also not contains)"],
  ["$.code matches \"^[A-Z]{3}$\"", "regular expression"],
  ["$.items.length == 3", "array size"],
  ["$.items[*].active all == true", "every element (any = at least one)"],
  ["$.items[code=\"X1\"].price == 10", "element where a field has a value"],
  ["$.value type == number", "type check"],
  ["$.deletedAt is null", "null check (also not null)"],
  ["header Content-Type contains \"json\"", "response header"],
];

function ExpectTab({ step, onChange }: { step: Step; onChange: (s: Step) => void }) {
  const model = (step.expect?.assertions ?? []).join("\n");
  const [text, setText] = useSyncedText(model);
  const [help, setHelp] = useState(false);
  const setLines = (t: string) => {
    const lines = t.split(/\r?\n/).map((l) => l.trimEnd()).filter((l) => l.trim());
    setText(t, lines.join("\n"));
    onChange({ ...step, expect: { ...step.expect, assertions: lines } });
  };
  return (
    <div className="col" style={{ gap: 12 }}>
      <div className="grid2" style={{ maxWidth: 620 }}>
        <Field label="HTTP status" hint="200, 2xx, 200,404 or <500. Empty: any status below 400 when no other check is set.">
          <input className="input mono" value={step.expect?.status ?? ""} placeholder="e.g. 200"
            onChange={(e) => onChange({ ...step, expect: { ...step.expect, status: e.target.value || undefined } })} />
        </Field>
        <Field label="Slower than this fails (ms)">
          <NumberInput value={step.expect?.maxTimeMs} placeholder="no limit" onChange={(v) => onChange({ ...step, expect: { ...step.expect, maxTimeMs: v } })} />
        </Field>
      </div>
      <Field label={<span className="row">Response checks, one per line <button className="btn ghost sm" onClick={() => setHelp(!help)}>{help ? "Hide syntax" : "Syntax"}</button></span>}>
        <CodeEditor value={text} lang="text" minHeight={110} maxHeight={420} placeholder={'$.id exists\n$.status == "ACTIVE"'} onChange={setLines} />
      </Field>
      {help ? (
        <table className="table" style={{ maxWidth: 760 }}>
          <tbody>
            {SYNTAX.map(([ex, what]) => (
              <tr key={ex} className="click" title="Click to add" onClick={() => setLines((text ? text.replace(/\n*$/, "\n") : "") + ex)}>
                <td className="mono">{ex}</td>
                <td className="muted">{what}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      <div className="faint" style={{ fontSize: 12 }}>Tip: after Send, hover a value in the response and click <b>assert</b> to add a check.</div>
    </div>
  );
}

function ExtractTab({ step, isSetup, onChange }: { step: Step; isSetup: boolean; onChange: (x: Extract[]) => void }) {
  const list = step.extract ?? [];
  const set = (i: number, p: Partial<Extract>) => onChange(list.map((e, j) => (j === i ? tidy({ ...e, ...p }) : e)));
  return (
    <div className="col" style={{ gap: 8 }}>
      <table className="kv">
        <tbody>
          {list.map((e, i) => (
            <tr key={i}>
              <td style={{ width: "26%" }}><input className="input sm mono" value={e.name} placeholder="variable name" onChange={(ev) => set(i, { name: ev.target.value })} /></td>
              <td><input className="input sm mono" value={e.path} placeholder="$.data.id" onChange={(ev) => set(i, { path: ev.target.value })} /></td>
              <td style={{ width: 170 }}>
                <select className="select sm" value={e.scope ?? ""} onChange={(ev) => set(i, { scope: (ev.target.value || undefined) as Extract["scope"] })}>
                  <option value="">{isSetup ? "Folder (setup)" : "This test case"}</option>
                  <option value="folder">Folder (shared)</option>
                  <option value="suite">Whole run</option>
                </select>
              </td>
              <td className="x"><Button variant="ghost" size="sm" icon={<Trash2 size={13} />} title="Remove" onClick={() => onChange(list.filter((_, j) => j !== i))} /></td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="row">
        <Button size="sm" onClick={() => onChange([...list, { name: "", path: "$." }])}>Add value</Button>
        <span className="faint" style={{ fontSize: 12 }}>Paths: <code>$.a.b[0]</code>, <code>$.list[-1]</code>, <code>$.list[code=X].id</code>, <code>$status</code>, <code>$header.Location</code>. Use the value later as <code>{"{{name}}"}</code>.</span>
      </div>
      <div className="faint" style={{ fontSize: 12 }}>
        Saved values stay inside the test case unless shared: <b>Folder</b> makes them visible to later test cases that depend on this one and to the folder teardown; <b>Whole run</b> to the suite teardown.
      </div>
    </div>
  );
}

function tidy(e: Extract): Extract {
  const out: Extract = { name: e.name, path: e.path };
  if (e.scope) out.scope = e.scope;
  return out;
}

function OptionsTab({ step, phase, onChange }: { step: Step; phase: Phase; onChange: (s: Step) => void }) {
  const o = step.options ?? {};
  const set = (p: Partial<StepOptions>) => onChange({ ...step, options: { ...o, ...p } });
  return (
    <div className="col" style={{ gap: 14, maxWidth: 720 }}>
      <div className="grid3">
        <Field label="Wait before sending (ms)"><NumberInput value={o.delayMs} placeholder="0" onChange={(v) => set({ delayMs: v })} /></Field>
        <Field label="Timeout (ms)"><NumberInput value={o.timeoutMs} placeholder="default" onChange={(v) => set({ timeoutMs: v })} /></Field>
        <div />
        <Field label="Retry when it does not pass (times)"><NumberInput value={o.retry?.times} placeholder="0" onChange={(v) => set({ retry: v ? { ...o.retry, times: v } : undefined })} /></Field>
        <Field label="Between retries (ms)"><NumberInput value={o.retry?.intervalMs} placeholder="1000" onChange={(v) => set({ retry: o.retry?.times ? { ...o.retry, intervalMs: v } : o.retry })} /></Field>
      </div>
      <div className="col" style={{ gap: 8 }}>
        <Check checked={o.compare !== false} onChange={(v) => set({ compare: v ? undefined : false })}>Compare baseline and target responses for this step</Check>
        {phase === "setup" ? (
          <Check checked={!!o.required} onChange={(v) => set({ required: v || undefined })}>Required: when this setup step does not pass, the folder's test cases are blocked</Check>
        ) : null}
        {phase === "pre" ? (
          <Check checked={!!o.required} onChange={(v) => set({ required: v || undefined })}>Required: when this precondition does not pass, the test case is blocked (otherwise it fails but its steps still run)</Check>
        ) : null}
      </div>
      {o.compare !== false ? (
        <Field label="Fields ignored by the comparison (on top of the suite settings)">
          <TagInput values={o.ignoreFields} onChange={(v) => set({ ignoreFields: v })} placeholder="e.g. createdAt, data.items.id" mono />
        </Field>
      ) : null}
      <Field label="Notes">
        <textarea className="textarea" rows={3} value={step.notes ?? ""} onChange={(e) => onChange({ ...step, notes: e.target.value || undefined })} />
      </Field>
    </div>
  );
}

export function CopyButton({ text, label = "Copy" }: { text: string; label?: string }) {
  return (
    <Button size="sm" variant="ghost" icon={<Copy size={13} />} onClick={async () => {
      await copyText(text);
      useStore.getState().toast("ok", "Copied");
    }}>{label}</Button>
  );
}
