import { useMemo, useState } from "react";
import type { ReactNode } from "react";
import { ArrowDown, ArrowUp, CopyPlus, Globe, KeyRound, Plus, Trash2 } from "lucide-react";
import { useStore } from "../store";
import type { Phase } from "../suiteOps";
import { allCases, allFolders, defaultsChain, stepsOf, withSteps } from "../suiteOps";
import type { AuthProfile, CompareOptions, Defaults, Environment, Settings, Step, Suite } from "../types";
import { RunPolicyForm } from "./FolderEditor";
import { KeyValueTable, isSecretKey } from "./KeyValueTable";
import { StepsWorkspace } from "./StepsWorkspace";
import { Button, Check, Empty, Field, NumberInput, TagInput, Tabs, openContextMenu } from "./ui";

const TABS = [
  { id: "general", label: "General" },
  { id: "environments", label: "Environments" },
  { id: "auth", label: "Auth" },
  { id: "variables", label: "Variables" },
  { id: "defaults", label: "Defaults" },
  { id: "hooks", label: "Setup & teardown" },
];

export function SuiteEditor() {
  const suite = useStore((s) => s.suite)!;
  const sel = useStore((s) => s.sel);
  const issues = useStore((s) => s.issues);
  const tab = TABS.some((t) => t.id === sel.tab) ? sel.tab! : "general";
  const edit = (fn: (s: Suite) => Suite, coalesce?: string) => useStore.getState().edit(fn, coalesce ? `suite:${coalesce}` : undefined);
  const suiteIssues = (section: string) => issues.filter((i) => !i.nodeId && i.section === section);
  const items = TABS.map((t) => ({
    ...t,
    count: t.id === "environments" ? suite.environments?.length : t.id === "auth" ? suite.authProfiles?.length : t.id === "variables" ? suite.variables?.length
      : t.id === "hooks" ? (suite.setup?.length ?? 0) + (suite.teardown?.length ?? 0) || undefined : undefined,
    dot: suiteIssues(t.id === "hooks" ? "setup" : t.id).length || (t.id === "hooks" && suiteIssues("teardown").length) ? ("bad" as const) : undefined,
  }));
  return (
    <div className="pane">
      <div className="head">
        <input className="input bare title-input grow" value={suite.name} placeholder="Suite name" onChange={(e) => edit((s) => ({ ...s, name: e.target.value }), "name")} />
      </div>
      <div style={{ background: "var(--surface)" }}>
        <Tabs items={items} value={tab} onChange={(t) => useStore.getState().select({ kind: "suite", tab: t })} />
      </div>
      {tab === "general" ? <General suite={suite} edit={edit} /> : null}
      {tab === "environments" ? <Environments suite={suite} edit={edit} /> : null}
      {tab === "auth" ? <AuthProfiles suite={suite} edit={edit} /> : null}
      {tab === "variables" ? (
        <div className="scroll">
          <div className="form">
            <div className="muted">Values every test case can use as <code>{"{{name}}"}</code>. An environment variable with the same name wins; values saved by steps win over both.</div>
            <KeyValueTable rows={suite.variables} onChange={(v) => edit((s) => ({ ...s, variables: v.length ? v : undefined }), "variables")} keyPlaceholder="name" secret={isSecretKey} />
          </div>
        </div>
      ) : null}
      {tab === "defaults" ? (
        <div className="scroll">
          <div className="form">
            <div className="muted">Applied to every step of the suite. Folders can add their own defaults; a step's own settings always win.</div>
            <DefaultsForm value={suite.defaults} onChange={(d) => edit((s) => ({ ...s, defaults: d }), "defaults")} />
          </div>
        </div>
      ) : null}
      {tab === "hooks" ? (
        <StepsWorkspace
          inherited={defaultsChain(suite, [])}
          sections={[
            { phase: "setup", label: "Suite setup", steps: stepsOf(suite, "setup"), hint: "Runs once before everything" },
            { phase: "teardown", label: "Suite teardown", steps: stepsOf(suite, "teardown"), hint: "Runs once at the very end" },
          ]}
          onSteps={(phase: Phase, steps: Step[], c?: string) => edit((s) => withSteps(s, phase, steps), c)}
          sel={{ phase: sel.phase, step: sel.step }}
          onSelect={(p, i) => useStore.getState().select({ kind: "suite", tab: "hooks", phase: p, step: i })}
          other={
            <div className="form">
              <div className="muted">
                Suite setup runs once before all folders; values it saves are available everywhere. Folder setup and teardown are edited on each folder.
                Steps marked <b>Required</b> block the test cases when they do not pass.
              </div>
            </div>
          }
        />
      ) : null}
    </div>
  );
}

function General({ suite, edit }: { suite: Suite; edit: (fn: (s: Suite) => Suite, c?: string) => void }) {
  const st = suite.settings ?? {};
  const setSettings = (p: Partial<Settings>, c?: string) => edit((s) => ({ ...s, settings: { ...s.settings, ...p } }), c);
  const co = st.compare ?? {};
  const setCompare = (p: Partial<CompareOptions>) => setSettings({ compare: { ...co, ...p } }, "compare");
  const stats = useMemo(() => {
    const cases = allCases(suite);
    const steps = cases.reduce((n, c) => n + (c.tc.steps?.length ?? 0) + (c.tc.preconditions?.length ?? 0) + (c.tc.cleanup?.length ?? 0), 0);
    return { cases: cases.length, folders: allFolders(suite).length, steps };
  }, [suite]);
  const envs = (suite.environments ?? []).map((e) => e.name);
  return (
    <div className="scroll">
      <div className="form">
        <div className="muted">{stats.folders} folders · {stats.cases} test cases · {stats.steps} steps</div>
        <Field label="Description">
          <textarea className="textarea" rows={3} value={suite.description ?? ""} onChange={(e) => edit((s) => ({ ...s, description: e.target.value || undefined }), "desc")} />
        </Field>
        <div className="grid3">
          <Field label="Version"><input className="input" value={suite.version ?? ""} onChange={(e) => edit((s) => ({ ...s, version: e.target.value || undefined }), "version")} /></Field>
          <Field label="Default environment">
            <select className="select" value={st.env ?? ""} onChange={(e) => setSettings({ env: e.target.value || undefined })}>
              <option value="">First in the list</option>
              {envs.map((n) => <option key={n}>{n}</option>)}
            </select>
          </Field>
          <Field label="Default baseline (compare)">
            <select className="select" value={st.baselineEnv ?? ""} onChange={(e) => setSettings({ baselineEnv: e.target.value || undefined })}>
              <option value="">None</option>
              {envs.map((n) => <option key={n}>{n}</option>)}
            </select>
          </Field>
        </div>
        <div className="section-title">Running</div>
        <div className="grid3">
          <Field label="Test cases at the same time" hint="Steps inside one test case always run in order.">
            <NumberInput value={st.concurrency} min={1} placeholder="4" onChange={(v) => setSettings({ concurrency: v }, "concurrency")} />
          </Field>
        </div>
        <RunPolicyForm value={st.run} onChange={(r) => setSettings({ run: r })} />
        <div className="section-title">Comparing baseline and target</div>
        <Field label="Fields ignored everywhere" hint="Dotted paths; a name without dots matches that field at any depth.">
          <TagInput values={co.ignoreFields} onChange={(v) => setCompare({ ignoreFields: v.length ? v : undefined })} mono placeholder="timestamp, data.id" />
        </Field>
        <div className="row wrap" style={{ gap: 18 }}>
          <Check checked={!!co.ignoreArrayOrder} onChange={(v) => setCompare({ ignoreArrayOrder: v || undefined })}>Ignore array order</Check>
          <Check checked={co.caseSensitive !== false} onChange={(v) => setCompare({ caseSensitive: v ? undefined : false })}>Text is case sensitive</Check>
          <Check checked={!!co.compareErrorResponses} onChange={(v) => setCompare({ compareErrorResponses: v || undefined })}>Also compare 5xx responses</Check>
        </div>
        <div className="grid3">
          <Field label="Number tolerance"><NumberInput value={co.numericTolerance} placeholder="0" onChange={(v) => setCompare({ numericTolerance: v })} /></Field>
        </div>
      </div>
    </div>
  );
}

function ListPane<T>({ items, selected, onSelect, label, icon, onAdd, onDuplicate, onDelete, onMove, children, empty }: {
  items: T[]; selected: number; onSelect: (i: number) => void; label: (t: T) => string; icon: ReactNode;
  onAdd: () => void; onDuplicate: (i: number) => void; onDelete: (i: number) => void; onMove: (from: number, to: number) => void;
  children: ReactNode; empty: string;
}) {
  return (
    <div className="work" style={{ gridTemplateColumns: "220px minmax(0, 1fr)" }}>
      <div className="steps-col">
        <div className="steps-section-head">
          <span>{items.length}</span>
          <Button size="sm" variant="ghost" icon={<Plus size={14} />} title="Add" onClick={onAdd} className="right" />
        </div>
        {items.map((it, i) => (
          <div key={i} className={`step-item${i === selected ? " active" : ""}`} onClick={() => onSelect(i)}
            onContextMenu={(e) => openContextMenu(e, [
              { label: "Duplicate", icon: <CopyPlus size={14} />, onClick: () => onDuplicate(i) },
              { label: "Move up", icon: <ArrowUp size={14} />, disabled: i === 0, onClick: () => onMove(i, i - 1) },
              { label: "Move down", icon: <ArrowDown size={14} />, disabled: i === items.length - 1, onClick: () => onMove(i, i + 1) },
              { sep: true },
              { label: "Delete", icon: <Trash2 size={14} />, danger: true, onClick: () => onDelete(i) },
            ])}>
            {icon}
            <span className="name">{label(it) || <span className="faint">(no name)</span>}</span>
          </div>
        ))}
      </div>
      <div className="scroll">{items.length ? children : <Empty>{empty}</Empty>}</div>
    </div>
  );
}

function move<T>(list: T[], from: number, to: number): T[] {
  if (to < 0 || to >= list.length) return list;
  const next = [...list];
  const [x] = next.splice(from, 1);
  next.splice(to, 0, x);
  return next;
}

function uniqueName(names: string[], base: string): string {
  if (!names.includes(base)) return base;
  for (let i = 2; ; i++) if (!names.includes(`${base}-${i}`)) return `${base}-${i}`;
}

function Environments({ suite, edit }: { suite: Suite; edit: (fn: (s: Suite) => Suite, c?: string) => void }) {
  const [i, setI] = useState(0);
  const list = suite.environments ?? [];
  const cur = list[Math.min(i, list.length - 1)];
  const idx = Math.min(i, list.length - 1);
  const setList = (l: Environment[], c?: string) => edit((s) => ({ ...s, environments: l }), c);
  const set = (p: Partial<Environment>, c?: string) => setList(list.map((e, j) => (j === idx ? { ...e, ...p } : e)), c ? `env${idx}:${c}` : undefined);
  const rename = (name: string) => {
    const old = cur.name;
    edit((s) => {
      const settings = { ...s.settings };
      if (settings.env === old) settings.env = name;
      if (settings.baselineEnv === old) settings.baselineEnv = name;
      return { ...s, settings, environments: (s.environments ?? []).map((e, j) => (j === idx ? { ...e, name } : e)) };
    }, `suite:env${idx}:name`);
    const st = useStore.getState();
    if (st.env === old) st.setEnv(name, st.baselineEnv);
    else if (st.baselineEnv === old) st.setEnv(st.env, name);
  };
  return (
    <ListPane items={list} selected={idx} onSelect={setI} label={(e) => e.name} icon={<Globe size={14} className="faint" />}
      empty="No environment yet. A suite needs at least one."
      onAdd={() => { setList([...list, { name: uniqueName(list.map((e) => e.name), "env"), baseUrl: "" }]); setI(list.length); }}
      onDuplicate={(j) => { setList([...list.slice(0, j + 1), { ...structuredClone(list[j]), name: uniqueName(list.map((e) => e.name), list[j].name + "-copy") }, ...list.slice(j + 1)]); setI(j + 1); }}
      onDelete={(j) => { setList(list.filter((_, k) => k !== j)); setI(Math.max(0, j - 1)); }}
      onMove={(a, b) => { setList(move(list, a, b)); setI(b); }}>
      {cur ? (
        <div className="form">
          <div className="grid2">
            <Field label="Name"><input className="input" value={cur.name} onChange={(e) => rename(e.target.value)} /></Field>
            <Field label="Authentication" hint="Used by steps that do not choose another profile.">
              <select className="select" value={cur.auth ?? ""} onChange={(e) => set({ auth: e.target.value || undefined })}>
                <option value="">None</option>
                {(suite.authProfiles ?? []).map((p) => <option key={p.name}>{p.name}</option>)}
              </select>
            </Field>
          </div>
          <Field label="Base URL" hint="Step URLs that start with / are added to it. {{variables}} and ${MACHINE_ENV_VAR} are allowed.">
            <input className="input mono" value={cur.baseUrl ?? ""} placeholder="https://host:port/api" onChange={(e) => set({ baseUrl: e.target.value }, "base")} />
          </Field>
          <Field label="Variables of this environment">
            <KeyValueTable rows={cur.variables} onChange={(v) => set({ variables: v.length ? v : undefined }, "vars")} keyPlaceholder="name" secret={isSecretKey} />
          </Field>
          <Field label="Headers sent to this environment">
            <KeyValueTable rows={cur.headers} onChange={(v) => set({ headers: v.length ? v : undefined }, "headers")} keyPlaceholder="Header" />
          </Field>
        </div>
      ) : null}
    </ListPane>
  );
}

function AuthProfiles({ suite, edit }: { suite: Suite; edit: (fn: (s: Suite) => Suite, c?: string) => void }) {
  const [i, setI] = useState(0);
  const list = suite.authProfiles ?? [];
  const idx = Math.min(i, list.length - 1);
  const cur = list[idx];
  const setList = (l: AuthProfile[], c?: string) => edit((s) => ({ ...s, authProfiles: l.length ? l : undefined }), c);
  const set = (p: Partial<AuthProfile>, c = "x") => setList(list.map((e, j) => (j === idx ? clean({ ...e, ...p }) : e)), `auth${idx}:${c}`);
  const text = (k: keyof AuthProfile, label: string, opts?: { secret?: boolean; mono?: boolean; hint?: string; placeholder?: string }) => (
    <Field label={label} hint={opts?.hint}>
      <input className={`input${opts?.mono !== false ? " mono" : ""}`} type={opts?.secret ? "password" : "text"} value={(cur[k] as string) ?? ""} placeholder={opts?.placeholder}
        onFocus={(e) => opts?.secret && (e.target.type = "text")} onBlur={(e) => opts?.secret && (e.target.type = "password")}
        onChange={(e) => set({ [k]: e.target.value || undefined } as Partial<AuthProfile>, k)} />
    </Field>
  );
  const grant = cur?.grantType ?? "client_credentials";
  return (
    <ListPane items={list} selected={idx} onSelect={setI} label={(p) => `${p.name} · ${p.type}`} icon={<KeyRound size={14} className="faint" />}
      empty="No auth profile. Add one, then pick it on an environment, a folder's defaults or a step."
      onAdd={() => { setList([...list, { name: uniqueName(list.map((p) => p.name), "auth"), type: "bearer" }]); setI(list.length); }}
      onDuplicate={(j) => { setList([...list.slice(0, j + 1), { ...structuredClone(list[j]), name: uniqueName(list.map((p) => p.name), list[j].name + "-copy") }, ...list.slice(j + 1)]); setI(j + 1); }}
      onDelete={(j) => { setList(list.filter((_, k) => k !== j)); setI(Math.max(0, j - 1)); }}
      onMove={(a, b) => { setList(move(list, a, b)); setI(b); }}>
      {cur ? (
        <div className="form">
          <div className="grid2">
            {text("name", "Name", { mono: false })}
            <Field label="Type">
              <select className="select" value={cur.type} onChange={(e) => set({ type: e.target.value })}>
                <option value="none">None</option>
                <option value="basic">Basic (user and password)</option>
                <option value="bearer">Bearer token</option>
                <option value="oauth2">OAuth 2 token request</option>
              </select>
            </Field>
          </div>
          {cur.type === "basic" ? <div className="grid2">{text("username", "User")}{text("password", "Password", { secret: true })}</div> : null}
          {cur.type === "bearer" ? text("token", "Token", { secret: true, hint: "Can be {{variable}} (for example a token saved by a setup step) or ${MACHINE_ENV_VAR}." }) : null}
          {cur.type === "oauth2" ? (
            <>
              <div className="grid2">
                <Field label="Grant">
                  <select className="select" value={grant} onChange={(e) => set({ grantType: e.target.value === "client_credentials" ? undefined : e.target.value })}>
                    <option value="client_credentials">Client credentials</option>
                    <option value="password">Password</option>
                    <option value="refresh_token">Refresh token</option>
                  </select>
                </Field>
                {text("tokenUrl", "Token URL", { placeholder: "https://idp/oauth/token" })}
                {text("clientId", "Client id")}
                {text("clientSecret", "Client secret", { secret: true })}
                {grant === "password" ? text("username", "User") : null}
                {grant === "password" ? text("password", "Password", { secret: true }) : null}
                {grant === "refresh_token" ? text("refreshToken", "Refresh token", { secret: true }) : null}
                {text("scope", "Scope")}
              </div>
              <Field label="Extra form fields of the token request">
                <KeyValueTable rows={cur.extraParams} onChange={(v) => set({ extraParams: v.length ? v : undefined }, "extra")} keyPlaceholder="field" />
              </Field>
              <div className="faint" style={{ fontSize: 12 }}>The token is requested once per run and refreshed when it expires.</div>
            </>
          ) : null}
          {text("description", "Description", { mono: false })}
          <div className="note">Secrets can stay out of the file: write <code>{"${MY_SECRET}"}</code> and set MY_SECRET on the machine that runs the tool.</div>
        </div>
      ) : null}
    </ListPane>
  );
}

function clean(p: AuthProfile): AuthProfile {
  const out = { ...p } as Record<string, unknown>;
  for (const k of Object.keys(out)) if (out[k] === undefined || out[k] === "") delete out[k];
  return out as unknown as AuthProfile;
}

export function DefaultsForm({ value, onChange }: { value?: Defaults; onChange: (d?: Defaults) => void }) {
  const suite = useStore((s) => s.suite)!;
  const set = (p: Partial<Defaults>) => {
    const next: Defaults = { ...value, ...p };
    if (!next.headers?.length) delete next.headers;
    if (!next.auth) delete next.auth;
    if (!next.timeoutMs) delete next.timeoutMs;
    onChange(Object.keys(next).length ? next : undefined);
  };
  return (
    <div className="col" style={{ gap: 12 }}>
      <div className="grid2">
        <Field label="Authentication">
          <select className="select" value={value?.auth ?? ""} onChange={(e) => set({ auth: e.target.value || undefined })}>
            <option value="">Inherit</option>
            <option value="none">None</option>
            {(suite.authProfiles ?? []).map((p) => <option key={p.name}>{p.name}</option>)}
          </select>
        </Field>
        <Field label="Timeout (ms)"><NumberInput value={value?.timeoutMs} placeholder="60000" onChange={(v) => set({ timeoutMs: v })} /></Field>
      </div>
      <Field label="Headers">
        <KeyValueTable rows={value?.headers} onChange={(h) => set({ headers: h })} keyPlaceholder="Header" />
      </Field>
    </div>
  );
}
