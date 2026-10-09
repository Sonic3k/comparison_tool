import { useMemo, useState } from "react";
import { Play, Replace, TriangleAlert, CircleAlert } from "lucide-react";
import { api } from "../api";
import { goToIssue } from "../nav";
import { useStore } from "../store";
import { allCases, caseLabel, find, replaceInSuite } from "../suiteOps";
import type { ReplaceOptions } from "../suiteOps";
import type { KeyValue, RunOptions, Selection, Step } from "../types";
import { fmtTime } from "../runs";
import { KeyValueTable } from "./KeyValueTable";
import { Button, Check, Field, Modal, NumberInput, TagInput } from "./ui";

export function CurlImport({ title = "Import a cURL command", onClose, onStep }: { title?: string; onClose: () => void; onStep: (s: Step) => void }) {
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const go = async () => {
    if (!text.trim()) return;
    setBusy(true);
    setError(null);
    try {
      onStep(await api.parseCurl(text));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal title={title} onClose={onClose} wide footer={
      <>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="primary" onClick={go} disabled={busy || !text.trim()}>Import</Button>
      </>
    }>
      <div className="muted">Paste a command copied from the browser dev tools, Postman or a terminal (bash or cmd style).</div>
      <textarea className="textarea mono" rows={10} value={text} onChange={(e) => setText(e.target.value)} data-autofocus
        placeholder={"curl 'https://api.example.com/items' -H 'Accept: application/json'"}
        onKeyDown={(e) => (e.ctrlKey || e.metaKey) && e.key === "Enter" && go()} />
      {error ? <div className="note bad">{error}</div> : null}
    </Modal>
  );
}

export function FindReplace({ onClose }: { onClose: () => void }) {
  const suite = useStore((s) => s.suite)!;
  const multi = useStore((s) => s.multi);
  const [o, setO] = useState<Omit<ReplaceOptions, "scope"> & { onlySelected: boolean }>({
    find: "", replace: "", regex: false, caseSensitive: true, onlySelected: false,
    fields: { url: true, headers: true, body: true, assertions: false, extract: false, names: false },
  });
  const scope = o.onlySelected && multi.length ? new Set(multi) : null;
  const preview = useMemo(() => (o.find ? replaceInSuite(suite, { ...o, scope }).count : 0), [suite, o, scope]);
  const apply = () => {
    const st = useStore.getState();
    const res = replaceInSuite(st.suite!, { ...o, scope });
    if (!res.count) return st.toast("info", "Nothing matched");
    st.edit(() => res.suite);
    st.toast("ok", `Replaced ${res.count} occurrence${res.count > 1 ? "s" : ""} (Ctrl+Z undoes it)`, { label: "Undo", run: () => useStore.getState().undo() });
    onClose();
  };
  const f = o.fields;
  const setF = (k: keyof typeof f, v: boolean) => setO({ ...o, fields: { ...f, [k]: v } });
  return (
    <Modal title="Find and replace in steps" icon={<Replace size={16} />} onClose={onClose} footer={
      <>
        <span className="muted" style={{ marginRight: "auto" }}>{o.find ? `${preview} match${preview === 1 ? "" : "es"}` : ""}</span>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="primary" disabled={!preview} onClick={apply}>Replace all</Button>
      </>
    }>
      <Field label="Find"><input className="input mono" value={o.find} onChange={(e) => setO({ ...o, find: e.target.value })} data-autofocus /></Field>
      <Field label="Replace with"><input className="input mono" value={o.replace} onChange={(e) => setO({ ...o, replace: e.target.value })} /></Field>
      <div className="row wrap" style={{ gap: 14 }}>
        <Check checked={o.caseSensitive} onChange={(v) => setO({ ...o, caseSensitive: v })}>Match case</Check>
        <Check checked={o.regex} onChange={(v) => setO({ ...o, regex: v })}>Regular expression ($1 in the replacement)</Check>
      </div>
      <div>
        <div className="field-label" style={{ marginBottom: 6 }}>Look in</div>
        <div className="row wrap" style={{ gap: 14 }}>
          <Check checked={f.url} onChange={(v) => setF("url", v)}>URLs and params</Check>
          <Check checked={f.headers} onChange={(v) => setF("headers", v)}>Headers</Check>
          <Check checked={f.body} onChange={(v) => setF("body", v)}>Bodies</Check>
          <Check checked={f.assertions} onChange={(v) => setF("assertions", v)}>Checks</Check>
          <Check checked={f.extract} onChange={(v) => setF("extract", v)}>Saved value paths</Check>
          <Check checked={f.names} onChange={(v) => setF("names", v)}>Step names</Check>
        </div>
      </div>
      <Check checked={o.onlySelected} onChange={(v) => setO({ ...o, onlySelected: v })}>
        Only in the items selected in the tree ({multi.length})
      </Check>
    </Modal>
  );
}

export function Shortcuts({ onClose }: { onClose: () => void }) {
  const rows: [string, string][] = [
    ["Ctrl+S", "Save the suite"],
    ["Ctrl+O", "Open a suite or a run result"],
    ["Ctrl+Z / Ctrl+Shift+Z", "Undo / redo (outside text fields)"],
    ["Ctrl+Enter", "Send the step being edited"],
    ["Ctrl+Shift+F", "Find and replace in steps"],
    ["/", "Search the list on the left"],
    ["Alt+1 / Alt+2", "Edit / Runs"],
    ["In the tree", "↑ ↓ move, ← → fold, Delete, Ctrl+D duplicate, Ctrl+click and Shift+click select several"],
    ["J / K", "Runs: next / previous test case"],
    ["[ / ]", "Runs: previous / next step"],
    ["E", "Runs: open the test case in the editor"],
    ["R", "Runs: run the test case again"],
    ["1 – 5, 0", "Runs: triage label (bug, test, environment, known, flaky), 0 clears"],
    ["?", "This list"],
  ];
  return (
    <Modal title="Keyboard shortcuts" onClose={onClose}>
      <table className="table">
        <tbody>
          {rows.map(([k, v]) => (
            <tr key={k}>
              <td className="nowrap"><kbd>{k}</kbd></td>
              <td>{v}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Modal>
  );
}

export function IssuesDialog({ onClose }: { onClose: () => void }) {
  const issues = useStore((s) => s.issues);
  const notes = useStore((s) => s.notes);
  return (
    <Modal title={issues.length ? `Problems (${issues.length})` : "Conversion notes"} onClose={onClose} wide>
      {notes.length ? (
        <div className="note">
          <div style={{ fontWeight: 600, marginBottom: 4 }}>Conversion notes</div>
          {notes.map((n, i) => <div key={i}>{n}</div>)}
        </div>
      ) : null}
      {issues.length ? (
        <div className="issues">
          {issues.map((i, n) => (
            <div key={n} className="issue" onClick={() => { goToIssue(i); onClose(); }}>
              {i.level === "error" ? <CircleAlert size={15} className="st-fail" /> : <TriangleAlert size={15} className="st-error" />}
              <div className="grow">
                <div>{i.message}</div>
                <div className="w">{i.where}</div>
              </div>
            </div>
          ))}
        </div>
      ) : notes.length ? null : <div className="muted">No problems found.</div>}
    </Modal>
  );
}

type Scope = "all" | "selected" | "tags";

export function RunDialog({ onClose, initialScope }: { onClose: () => void; initialScope?: Scope }) {
  const suite = useStore((s) => s.suite)!;
  const multi = useStore((s) => s.multi);
  const runs = useStore((s) => s.runs);
  const env = useStore((s) => s.env);
  const baselineEnv = useStore((s) => s.baselineEnv);
  const allTags = useMemo(() => [...new Set(allCases(suite).flatMap((c) => c.tc.tags ?? []))].sort(), [suite]);
  const reusable = runs.filter((r) => r.hooks.some((h) => h.hook === "setup" && h.status === "pass" && h.vars && Object.keys(h.vars).length));
  const [scope, setScope] = useState<Scope>(initialScope ?? (multi.length > 1 ? "selected" : "all"));
  const [tags, setTags] = useState<string[]>([]);
  const [concurrency, setConcurrency] = useState<number | undefined>(undefined);
  const [capture, setCapture] = useState("auto");
  const [bodyLimit, setBodyLimit] = useState<number | undefined>(undefined);
  const [hooks, setHooks] = useState<"run" | "reuse" | "skip">("run");
  const [reuseFrom, setReuseFrom] = useState(reusable[0]?.runId ?? "");
  const [vars, setVars] = useState<KeyValue[]>([]);

  const selection: Selection | undefined = scope === "selected" ? selectionOf(multi) : scope === "tags" ? { tags } : undefined;
  const start = () => {
    const options: RunOptions = {};
    if (concurrency) options.concurrency = concurrency;
    if (capture !== "auto") options.capture = capture;
    if (bodyLimit) options.bodyLimitKb = bodyLimit;
    if (hooks !== "run") options.hooks = hooks;
    if (hooks === "reuse") {
      const r = runs.find((x) => x.runId === reuseFrom);
      if (r) options.reuse = reuseMap(r.hooks);
    }
    const v = vars.filter((x) => x.key && !x.disabled);
    if (v.length) options.variables = Object.fromEntries(v.map((x) => [x.key, x.value]));
    useStore.getState().startRun(selection, options);
    onClose();
  };
  return (
    <Modal title="Run with options" icon={<Play size={16} />} onClose={onClose} wide footer={
      <>
        <span className="muted" style={{ marginRight: "auto" }}>{env}{baselineEnv ? ` compared with ${baselineEnv}` : ""}</span>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="run" icon={<Play size={14} />} onClick={start} disabled={(scope === "tags" && !tags.length) || (scope === "selected" && !multi.length)}>Run</Button>
      </>
    }>
      <Field label="What to run">
        <div className="segmented">
          <button className={scope === "all" ? "on" : ""} onClick={() => setScope("all")}>Whole suite</button>
          <button className={scope === "selected" ? "on" : ""} onClick={() => setScope("selected")} disabled={!multi.length}>Selected in the tree ({multi.length})</button>
          <button className={scope === "tags" ? "on" : ""} onClick={() => setScope("tags")}>By tag</button>
        </div>
      </Field>
      {scope === "tags" ? (
        <Field label="Tags" hint="Test cases with any of these tags, and the test cases they depend on.">
          <TagInput values={tags} onChange={setTags} suggestions={allTags} placeholder="type a tag" />
        </Field>
      ) : null}
      <div className="grid3">
        <Field label="Test cases at the same time" hint={`Suite setting: ${suite.settings?.concurrency ?? 4}`}>
          <NumberInput value={concurrency} min={1} placeholder={String(suite.settings?.concurrency ?? 4)} onChange={setConcurrency} />
        </Field>
        <Field label="Keep response bodies" hint="Auto: all for small runs, otherwise only for test cases that did not pass">
          <select className="select" value={capture} onChange={(e) => setCapture(e.target.value)}>
            <option value="auto">Auto</option>
            <option value="all">All</option>
            <option value="failed">Only not passed</option>
            <option value="none">None</option>
          </select>
        </Field>
        <Field label="Body limit (KB)"><NumberInput value={bodyLimit} placeholder="256" onChange={setBodyLimit} /></Field>
      </div>
      <Field label="Setup and teardown" hint={hooks === "reuse" ? "Setup and teardown do not run; the values setup saved in that run are used." : undefined}>
        <div className="row wrap">
          <div className="segmented">
            <button className={hooks === "run" ? "on" : ""} onClick={() => setHooks("run")}>Run them</button>
            <button className={hooks === "reuse" ? "on" : ""} onClick={() => setHooks("reuse")} disabled={!reusable.length}>Reuse values of a run</button>
            <button className={hooks === "skip" ? "on" : ""} onClick={() => setHooks("skip")}>Skip</button>
          </div>
          {hooks === "reuse" ? (
            <select className="select" style={{ width: 280 }} value={reuseFrom} onChange={(e) => setReuseFrom(e.target.value)}>
              {reusable.map((r) => <option key={r.runId} value={r.runId}>{r.header.env} · {fmtTime(r.header.startedAt)} · {r.runId}</option>)}
            </select>
          ) : null}
        </div>
      </Field>
      <Field label="Override variables for this run">
        <KeyValueTable rows={vars} onChange={setVars} keyPlaceholder="variable" />
      </Field>
    </Modal>
  );
}

export function selectionOf(ids: string[]): Selection {
  const suite = useStore.getState().suite;
  const caseIds: string[] = [];
  const folderIds: string[] = [];
  for (const id of ids) {
    const loc = suite ? find(suite, id) : null;
    if (!loc) continue;
    if (loc.node.type === "case") caseIds.push(id);
    else folderIds.push(id);
  }
  const sel: Selection = {};
  if (caseIds.length) sel.caseIds = caseIds;
  if (folderIds.length) sel.folderIds = folderIds;
  return sel;
}

export function reuseMap(hooks: { hook: string; folderId?: string; vars?: Record<string, Record<string, string>> }[]): Record<string, Record<string, Record<string, string>>> {
  const out: Record<string, Record<string, Record<string, string>>> = {};
  for (const h of hooks) if (h.hook === "setup" && h.vars) out[h.folderId ?? "suite"] = h.vars;
  return out;
}

export function describeSelection(ids: string[]): string {
  const suite = useStore.getState().suite;
  if (!suite || !ids.length) return "";
  if (ids.length === 1) {
    const loc = find(suite, ids[0]);
    if (!loc) return "";
    return loc.node.type === "case" ? caseLabel(loc.node) : `folder ${loc.node.name}`;
  }
  return `${ids.length} items`;
}
