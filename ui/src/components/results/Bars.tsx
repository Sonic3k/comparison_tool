import { useEffect, useRef, useState } from "react";
import {
  Check, ChevronDown, ChevronsDownUp, ChevronsUpDown, CircleStop, ClipboardCopy, Columns3, Ellipsis, Eraser, FileSpreadsheet, FileText,
  FolderOpen, History, Layers, Play, RotateCcw, Save, Search, StickyNote, X,
} from "lucide-react";
import { exportReport, historyEntries, openHistory, pickRunFile, saveRunResult } from "../../actions";
import type { HistoryEntry } from "../../actions";
import type { RunView } from "../../runs";
import { fmtMs, fmtSize, fmtTime, latestHooks } from "../../runs";
import type { ResultsData } from "../../results/compute";
import { earlierRuns } from "../../results/history";
import type { Delta, Filter, GroupBy } from "../../results/model";
import { LABELS, listText, tableText } from "../../results/model";
import { runAgainInPlace, startOver } from "../../results/rerun";
import { COLUMNS, useStore } from "../../store";
import { Button, DropMenu, Modal, StatusIcon, copyText, useOverlay } from "../ui";
import { Box } from "./ResultsTable";

// ---------------------------------------------------------------- run bar

export function RunBar({ data }: { data: ResultsData }) {
  const run = data.run!;
  const st = useStore.getState();
  const c = data.counts;
  const live = run.source === "live" && !run.done;
  const ran = c.all - c.disabled;
  const finished = c.pass + c.fail + c.error + c.blocked + c.skipped;
  const rate = finished ? Math.round((c.pass / finished) * 1000) / 10 : 0;
  const notPassedIds = data.infos.filter((i) => ["fail", "error", "blocked", "skipped"].includes(i.state)).map((i) => i.id);
  const reusable = latestHooks(run).some((h) => h.hook === "setup" && h.vars && Object.keys(h.vars).length);
  const checkedIds = Object.keys(useStore((s) => s.checked));
  const reruns = run.header.reruns ?? [];
  const capture = run.header.capture === "all" ? "every response kept" : run.header.capture === "none" ? "no responses kept" : "responses kept when a test case did not pass";
  return (
    <div className="runbar">
      <div className="rb">
        <RunPicker run={run} />
        <span className="runmeta">
          <span><b>{ran}</b> test case{ran === 1 ? "" : "s"}{c.disabled ? <>, <b>{c.disabled}</b> disabled</> : null}</span>
          <span>{run.header.env}{run.header.baselineEnv ? <> against <b>{run.header.baselineEnv}</b></> : null}</span>
          {live ? <span><b>{finished}</b> of {ran} done</span> : run.summary?.durationMs !== undefined ? <span>took <b>{fmtMs(run.summary.durationMs)}</b></span> : null}
          {run.header.concurrency ? <span>{run.header.concurrency} at a time</span> : null}
          <span>{capture}</span>
          {reruns.length ? <span title={reruns.map((r) => `${fmtTime(r.startedAt)}: ${r.caseIds.length} test case(s)`).join("\n")}>{reruns.reduce((n, r) => n + r.caseIds.length, 0)} ran again</span> : null}
        </span>
        <span className="grow" />
        {live ? (
          <Button variant="stop" icon={<CircleStop size={14} />} onClick={() => st.stopRun(run.runId)}>Stop</Button>
        ) : run.rerun ? (
          <Button variant="stop" icon={<CircleStop size={14} />} onClick={() => st.stopRun(run.rerun!.runId)}
            title="Stop running these test cases again; the results so far stay in this run">Stop ({run.rerun.caseIds.length})</Button>
        ) : (
          <span className="split-btn">
            <Button variant="run" icon={run.rerun ? <RotateCcw size={14} className="spin" /> : <Play size={14} />} disabled={!notPassedIds.length || !!run.rerun}
              onClick={() => runAgainInPlace(run, notPassedIds)} title="Run every test case that did not pass again; the results are added to this run">
              Run again{notPassedIds.length ? ` (${notPassedIds.length})` : ""}
            </Button>
            <DropMenu align="right" trigger={({ toggle }) => <Button variant="run" className="split-r" icon={<ChevronDown size={14} />} onClick={toggle} disabled={!!run.rerun} title="More ways to run" />}
              items={[
                { header: "Added to this run" },
                { label: `Not passed (${notPassedIds.length})`, icon: <Play size={14} />, disabled: !notPassedIds.length, onClick: () => runAgainInPlace(run, notPassedIds) },
                { label: "Not passed, reusing this run's setup values", icon: <Play size={14} />, disabled: !notPassedIds.length || !reusable, onClick: () => runAgainInPlace(run, notPassedIds, { reuseSetup: true }) },
                { label: `Selected (${checkedIds.length})`, icon: <Play size={14} />, disabled: !checkedIds.length, onClick: () => runAgainInPlace(run, checkedIds) },
                { sep: true },
                { label: "Clear the results and run everything again", icon: <Eraser size={14} />, hint: "new run", onClick: () => startOver(run) },
              ]} />
          </span>
        )}
        <Button icon={<Save size={14} />} onClick={() => saveRunResult(run)} disabled={live}
          title={run.dirty ? "Labels, notes or runs added since the last save" : "Keep this result as a .run.jsonl file"}>
          Save result{run.dirty ? <span className="dot" /> : null}
        </Button>
        <DropMenu align="right" trigger={({ toggle }) => <Button icon={<FileSpreadsheet size={14} />} onClick={toggle} disabled={live}>Export <ChevronDown size={13} /></Button>}
          items={[
            { label: "Excel report", icon: <FileSpreadsheet size={14} />, onClick: () => exportReport(run, "excel") },
            { label: "JUnit XML", icon: <FileText size={14} />, onClick: () => exportReport(run, "junit") },
            { sep: true },
            { label: "Copy what did not pass, as a list", icon: <ClipboardCopy size={14} />, onClick: () => copyInfos(data.infos.filter((i) => notPassedIds.includes(i.id)), "list") },
            { label: "Copy what did not pass, as a table", icon: <ClipboardCopy size={14} />, onClick: () => copyInfos(data.infos.filter((i) => notPassedIds.includes(i.id)), "table") },
          ]} />
        <DropMenu align="right" trigger={({ toggle }) => <Button variant="ghost" icon={<Ellipsis size={16} />} onClick={toggle} title="More" />}
          items={[
            { label: "Open a result file…", icon: <FolderOpen size={14} />, onClick: pickRunFile },
            { sep: true },
            { label: "Close this run", icon: <X size={14} />, onClick: () => st.removeRun(run.runId) },
          ]} />
      </div>
      <div className="rb">
        <BigBar data={data} />
        <span className="rate"><b>{finished ? `${rate}%` : "–"}</b> passed</span>
        <CompareBar data={data} />
      </div>
    </div>
  );
}

function BigBar({ data }: { data: ResultsData }) {
  const c = data.counts;
  const total = Math.max(1, c.all);
  const seg = (n: number, cls: string, title: string) => (n ? <i className={cls} style={{ width: `${(n / total) * 100}%` }} title={`${n} ${title}`} /> : null);
  return (
    <span className="bigbar">
      {seg(c.pass, "pass", "passed")}{seg(c.fail, "fail", "failed")}{seg(c.error, "error", "error")}{seg(c.blocked, "blocked", "blocked")}
      {seg(c.skipped, "skipped", "skipped")}{seg(c.running, "running", "running")}{seg(c.queued, "queued", "waiting")}{seg(c.disabled, "disabled", "disabled")}
    </span>
  );
}

function CompareBar({ data }: { data: ResultsData }) {
  const run = data.run!;
  const st = useStore.getState();
  const delta = useStore((s) => s.delta);
  const pick = useStore((s) => s.compareWith);
  const earlier = earlierRuns(run, data.past);
  const ch = data.changes;
  if (!data.compared) {
    return (
      <span className="vs">
        {pick === "none" ? <button className="link" onClick={() => st.setCompareWith("auto")}>Compare with the run before</button>
          : <span className="faint">No earlier run of this suite on {run.header.env} to compare with</span>}
      </span>
    );
  }
  const chip = (d: Delta, n: number, text: string) => (
    <button className={`dchip ${d}${delta === d ? " on" : ""}`} disabled={!n && delta !== d} onClick={() => st.setDelta(delta === d ? undefined : d)}
      title={delta === d ? "Show them all again" : `Show only these`}>
      <b>{n}</b> {text}{delta === d ? <X size={12} /> : null}
    </button>
  );
  return (
    <span className="vs">
      <span className="faint">compared with</span>
      <DropMenu trigger={({ toggle }) => (
        <button className="vs-pick" onClick={toggle}>{fmtTime(data.compared!.startedAt)}<ChevronDown size={12} /></button>
      )} items={[
        { header: "Compare with" },
        ...earlier.slice(0, 12).map((p, i) => ({
          label: `${fmtTime(p.startedAt)}${i === 0 ? " (the run before)" : ""}`, icon: p.runId === data.compared?.runId ? <Check size={14} /> : undefined,
          onClick: () => st.setCompareWith(i === 0 ? "auto" : p.runId),
        })),
        { sep: true },
        { label: "Nothing", onClick: () => st.setCompareWith("none") },
      ]} />
      {chip("new", ch.new, ch.new === 1 ? "new failure" : "new failures")}
      {chip("still", ch.still, "still failing")}
      {chip("fixed", ch.fixed, "fixed")}
    </span>
  );
}

function RunPicker({ run }: { run: RunView }) {
  const runs = useStore((s) => s.runs);
  const serverFile = useStore((s) => s.serverFile);
  const resultsDir = useStore((s) => s.resultsDir);
  const [open, setOpen] = useState(false);
  const [saved, setSaved] = useState<HistoryEntry[] | null>(null);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const down = (e: MouseEvent) => ref.current && !ref.current.contains(e.target as globalThis.Node) && setOpen(false);
    const key = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    document.addEventListener("mousedown", down, true);
    document.addEventListener("keydown", key, true);
    return () => {
      document.removeEventListener("mousedown", down, true);
      document.removeEventListener("keydown", key, true);
    };
  }, [open]);
  const toggle = async () => {
    if (open) return setOpen(false);
    setOpen(true);
    if (serverFile || resultsDir) {
      setSaved(null);
      try {
        setSaved(await historyEntries());
      } catch {
        setSaved([]);
      }
    }
  };
  const st = useStore.getState();
  const inMemory = new Set(runs.map((r) => r.fileName ?? r.serverName).filter(Boolean));
  return (
    <div className="menu-wrap" ref={ref}>
      <button className="picker" onClick={toggle} title="Pick another run">
        <History size={15} className="muted" />
        <span><b>{fmtTime(run.header.startedAt)}</b></span>
        {run.source === "file" ? <span className="faint small">saved</span> : null}
        <ChevronDown size={14} className="muted" />
      </button>
      {open ? (
        <div className="menu left picker-menu">
          <div className="menu-head">This session</div>
          {runs.map((r) => {
            const c = Object.values(r.cases);
            const pass = c.filter((x) => x.status === "pass").length;
            return (
              <button key={r.runId} className={`menu-item pick${r.runId === run.runId ? " cur" : ""}`} onClick={() => { st.setActiveRun(r.runId); setOpen(false); }}>
                <StatusIcon status={!r.done ? "running" : pass === c.length ? "pass" : "fail"} size={14} />
                <span className="grow ellipsis"><b>{fmtTime(r.header.startedAt)}</b> {r.header.env}{r.header.baselineEnv ? ` vs ${r.header.baselineEnv}` : ""}</span>
                <span className="faint small">{r.done ? `${pass}/${c.length} passed` : "running"}</span>
              </button>
            );
          })}
          {serverFile || resultsDir ? (
            <>
              <div className="menu-sep" />
              <div className="menu-head">Saved results</div>
              {saved === null ? <div className="menu-note">Loading…</div> : null}
              {saved && !saved.length ? <div className="menu-note">None yet</div> : null}
              {(saved ?? []).filter((h) => !inMemory.has(h.name)).slice(0, 30).map((h) => (
                <button key={h.name} className="menu-item pick" onClick={() => { setOpen(false); openHistory(h); }} title={h.name}>
                  <History size={14} className="faint" />
                  <span className="grow ellipsis">{h.name.replace(/\.run\.jsonl$/, "")}</span>
                  <span className="faint small">{fmtTime(h.modified)} · {fmtSize(h.size)}</span>
                </button>
              ))}
            </>
          ) : null}
          <div className="menu-sep" />
          <button className="menu-item" onClick={() => { setOpen(false); pickRunFile(); }}><FolderOpen size={14} />Open a result file…</button>
        </div>
      ) : null}
    </div>
  );
}

// ---------------------------------------------------------------- filters, grouping, search

const GROUPS: { id: GroupBy; label: string }[] = [
  { id: "folder", label: "Folder" },
  { id: "cause", label: "Failure cause" },
  { id: "status", label: "Status" },
  { id: "label", label: "Label" },
  { id: "none", label: "No groups" },
];

export function Toolbar({ data }: { data: ResultsData }) {
  const filter = useStore((s) => s.filter);
  const delta = useStore((s) => s.delta);
  const group = useStore((s) => s.group);
  const search = useStore((s) => s.search);
  const bodies = useStore((s) => s.searchBodies);
  const columns = useStore((s) => s.columns);
  const st = useStore.getState();
  const c = data.counts;
  const searchRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    const f = () => searchRef.current?.focus();
    window.addEventListener("ct:search", f);
    return () => window.removeEventListener("ct:search", f);
  }, []);
  const chips: { id: Filter; label: string; n: number; show: boolean; icon?: string }[] = [
    { id: "notpass", label: "Not passed", n: c.notpass, show: true },
    { id: "fail", label: "Failed", n: c.fail, show: true, icon: "fail" },
    { id: "error", label: "Error", n: c.error, show: true, icon: "error" },
    { id: "blocked", label: "Blocked", n: c.blocked, show: true, icon: "blocked" },
    { id: "skipped", label: "Skipped", n: c.skipped, show: c.skipped > 0, icon: "skipped" },
    { id: "pass", label: "Passed", n: c.pass, show: true, icon: "pass" },
    { id: "disabled", label: "Disabled", n: c.disabled, show: c.disabled > 0, icon: "disabled" },
    { id: "all", label: "All", n: c.all, show: true },
  ];
  const groups = data.rows.filter((r) => r.kind === "group" && r.canOpen);
  const anyOpen = groups.some((r) => r.kind === "group" && r.open);
  return (
    <div className="rtools">
      <div className="fchips">
        {chips.filter((x) => x.show).map((x) => (
          <button key={x.id} className={`fchip${filter === x.id ? " on" : ""}${!x.n && x.id !== "all" && x.id !== "notpass" ? " zero" : ""}`} onClick={() => st.setFilter(x.id)}>
            {x.icon ? <StatusIcon status={x.icon} size={13} /> : null}{x.label}<span className="n">{x.n}</span>
          </button>
        ))}
        {delta ? (
          <button className={`fchip on delta ${delta}`} onClick={() => st.setDelta(undefined)} title="Show them all again">
            {delta === "new" ? "New failures" : delta === "still" ? "Still failing" : "Fixed"}<X size={12} />
          </button>
        ) : null}
      </div>
      <span className="grow" />
      <label className="grp-pick" title="How the list is grouped">
        <Layers size={14} />
        <select value={group} onChange={(e) => st.setGroup(e.target.value as GroupBy)}>
          {GROUPS.map((g) => <option key={g.id} value={g.id}>{g.label}</option>)}
        </select>
        <ChevronDown size={13} />
      </label>
      <div className="rsearch">
        <Search size={14} />
        <input ref={searchRef} value={search} placeholder={bodies ? "Search, also in requests and responses" : "Search key, name, reason, note   ( / )"} spellCheck={false}
          onChange={(e) => st.setSearch(e.target.value)} onKeyDown={(e) => { if (e.key === "Escape") { st.setSearch(""); (e.target as HTMLInputElement).blur(); } }} />
        {search ? <button className="x" onClick={() => st.setSearch("")} title="Clear"><X size={12} /></button> : null}
        <button className={`in-bodies${bodies ? " on" : ""}`} onClick={() => st.setSearchBodies(!bodies)}
          title="Also search the requests, responses and variable values of every step that kept them">
          {"{ }"} in responses
        </button>
      </div>
      <Button variant="ghost" icon={anyOpen ? <ChevronsDownUp size={15} /> : <ChevronsUpDown size={15} />} disabled={!groups.length}
        title={anyOpen ? "Close every group" : "Open every group"} onClick={() => st.setGroupOpen(groups.map((g) => g.id), !anyOpen)} />
      <DropMenu align="right" trigger={({ toggle }) => <Button variant="ghost" icon={<Columns3 size={15} />} onClick={toggle} title="Columns" />}
        items={() => [
          { header: "Columns" },
          ...COLUMNS.map((col) => ({ label: col.label, icon: columns[col.id] ? <Check size={14} /> : undefined, onClick: () => st.setColumn(col.id, !columns[col.id]) })),
        ]} />
    </div>
  );
}

// ---------------------------------------------------------------- what to do with the selected test cases

export function BatchBar({ data }: { data: ResultsData }) {
  const checked = useStore((s) => s.checked);
  const run = data.run!;
  const st = useStore.getState();
  const ids = Object.keys(checked).filter((id) => data.infos.some((i) => i.id === id));
  if (!ids.length) return null;
  const infos = data.infos.filter((i) => checked[i.id]);
  const withResults = infos.filter((i) => i.result).map((i) => i.id);
  const reusable = latestHooks(run).some((h) => h.hook === "setup" && h.vars && Object.keys(h.vars).length);
  const label = (id: string | null) => st.setTriage(run.runId, withResults, { label: id });
  return (
    <div className="batch">
      <span className="count" onClick={() => st.clearChecked()} title="Clear the selection"><Box on />{ids.length} selected</span>
      <Button size="sm" variant="run" icon={<Play size={13} />} disabled={!run.done || !!run.rerun} onClick={() => runAgainInPlace(run, ids)}
        title="Run them again; the results are added to this run">Run again</Button>
      <Button size="sm" icon={<Play size={13} />} disabled={!run.done || !!run.rerun || !reusable} onClick={() => runAgainInPlace(run, ids, { reuseSetup: true })}
        title="Run them again without their folder setups, using the values those setups saved in this run">Reuse setup values</Button>
      <span className="bsep" />
      <span className="faint small">Label</span>
      {LABELS.map((l) => (
        <button key={l.id} className={`lab ${l.label.toLowerCase()}`} title={l.hint} disabled={!withResults.length} onClick={() => label(l.id)}>{l.label}</button>
      ))}
      <button className="lab ghost" disabled={!withResults.length} onClick={() => label(null)} title="Remove the labels">None</button>
      <Button size="sm" icon={<StickyNote size={13} />} disabled={!withResults.length} onClick={() => noteDialog(run, withResults)}>Note…</Button>
      <span className="bsep" />
      <DropMenu trigger={({ toggle }) => <Button size="sm" icon={<ClipboardCopy size={13} />} onClick={toggle}>Copy <ChevronDown size={12} /></Button>}
        items={[
          { label: "As a list, to paste in a message or ticket", onClick: () => copyInfos(infos, "list") },
          { label: "As a table, to paste in Excel or Teams", onClick: () => copyInfos(infos, "table") },
        ]} />
      <DropMenu trigger={({ toggle }) => <Button size="sm" icon={<FileSpreadsheet size={13} />} onClick={toggle} disabled={!run.done}>Export <ChevronDown size={12} /></Button>}
        items={[
          { label: "Excel report of these", icon: <FileSpreadsheet size={14} />, onClick: () => exportReport(run, "excel", new Set(ids)) },
          { label: "JUnit XML of these", icon: <FileText size={14} />, onClick: () => exportReport(run, "junit", new Set(ids)) },
        ]} />
      <span className="grow" />
      <Button size="sm" variant="ghost" icon={<X size={13} />} onClick={() => st.clearChecked()}>Clear</Button>
    </div>
  );
}

async function copyInfos(infos: ResultsData["infos"], how: "list" | "table") {
  await copyText(how === "list" ? listText(infos) : tableText(infos));
  useStore.getState().toast("ok", `${infos.length} test case${infos.length === 1 ? "" : "s"} copied as a ${how}`);
}

function noteDialog(run: RunView, ids: string[]) {
  let id = 0;
  const close = () => useOverlay.getState().closeDialog(id);
  id = useOverlay.getState().showDialog(<NoteDialog run={run} ids={ids} onClose={close} />);
}

function NoteDialog({ run, ids, onClose }: { run: RunView; ids: string[]; onClose: () => void }) {
  const [text, setText] = useState("");
  const save = () => {
    useStore.getState().setTriage(run.runId, ids, { note: text });
    onClose();
  };
  return (
    <Modal title={`Note for ${ids.length} test case${ids.length > 1 ? "s" : ""}`} onClose={onClose}
      footer={<><Button onClick={onClose}>Cancel</Button><Button variant="primary" onClick={save}>Save note</Button></>}>
      <textarea className="textarea" rows={3} autoFocus placeholder="Ticket, cause, who is looking at it…" value={text} onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => { if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) save(); }} />
      <div className="faint" style={{ fontSize: 12 }}>Replaces their current notes. Ctrl+Enter saves.</div>
    </Modal>
  );
}
