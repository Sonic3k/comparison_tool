import { useEffect, useMemo, useRef, useState } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { FolderOpen, GitCompare, History, ListTree, Rows3, Search, SlidersHorizontal, X } from "lucide-react";
import { historyEntries, openHistory, pickRunFile } from "../actions";
import type { HistoryEntry } from "../actions";
import { fmtMs, fmtSize, fmtTime } from "../runs";
import type { RunView } from "../runs";
import { runRows, stateCounts } from "../runRows";
import type { RunRow } from "../runRows";
import { useStore } from "../store";
import type { Filter } from "../store";
import { Button, StatusIcon } from "./ui";

let savedScroll = 0;

export const TRIAGE = [
  { id: "bug", label: "Product bug", key: "1" },
  { id: "test", label: "Test issue", key: "2" },
  { id: "env", label: "Environment", key: "3" },
  { id: "known", label: "Known / expected", key: "4" },
  { id: "flaky", label: "Flaky", key: "5" },
];

export function runLabel(r: RunView): string {
  const c = stateCounts(r);
  const state = r.done ? `${c.pass}/${c.all} pass` : `running ${c.all - c.queued - c.running}/${c.all}`;
  return `${fmtTime(r.header.startedAt)} · ${r.header.env}${r.header.baselineEnv ? " vs " + r.header.baselineEnv : ""} · ${state}${r.source === "file" ? " · file" : ""}`;
}

export function useRunRows(): { run?: RunView; rows: RunRow[] } {
  const runs = useStore((s) => s.runs);
  const activeRun = useStore((s) => s.activeRun);
  const suite = useStore((s) => s.suite);
  const filter = useStore((s) => s.filter);
  const search = useStore((s) => s.search);
  const grouping = useStore((s) => s.grouping);
  const compareWith = useStore((s) => s.compareWith);
  const run = runs.find((r) => r.runId === activeRun);
  const older = runs.find((r) => r.runId === compareWith);
  const rows = useMemo(() => (run ? runRows({ run, suite, filter, search, grouping, older }) : []), [run, suite, filter, search, grouping, older]);
  return { run, rows };
}

export function RunsSidebar() {
  const runs = useStore((s) => s.runs);
  const runSel = useStore((s) => s.runSel);
  const filter = useStore((s) => s.filter);
  const search = useStore((s) => s.search);
  const grouping = useStore((s) => s.grouping);
  const compareWith = useStore((s) => s.compareWith);
  const { run, rows } = useRunRows();
  const listRef = useRef<HTMLDivElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const counts = useMemo(() => (run ? stateCounts(run) : null), [run]);

  const v = useVirtualizer({
    count: rows.length,
    getScrollElement: () => listRef.current,
    estimateSize: (i) => (rows[i]?.kind === "group" ? 30 : 30),
    overscan: 16,
    initialOffset: savedScroll,
  });

  const selectedId = runSel.caseId ? `c:${runSel.caseId}` : runSel.hook ?? null;
  useEffect(() => {
    if (!selectedId) return;
    const i = rows.findIndex((r) => r.id === selectedId);
    if (i >= 0) v.scrollToIndex(i, { align: "auto" });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId]);

  useEffect(() => {
    const onFocusSearch = () => searchRef.current?.focus();
    window.addEventListener("ct:search", onFocusSearch);
    return () => window.removeEventListener("ct:search", onFocusSearch);
  }, []);

  if (!runs.length) {
    return (
      <div className="sidebar">
        <div className="empty">
          <div className="col">
            <div>No runs yet.</div>
            <div className="faint" style={{ fontSize: 12 }}>Run the suite, a folder or a test case, or open a saved result.</div>
            <div className="row">
              <Button icon={<FolderOpen size={14} />} onClick={pickRunFile}>Open result…</Button>
              <HistoryButton label />
            </div>
          </div>
        </div>
      </div>
    );
  }

  const chips: { id: Filter; label: string; n?: number }[] = [
    { id: "notpass", label: "Not passed", n: counts?.notpass },
    { id: "all", label: "All", n: counts?.all },
    { id: "fail", label: "Fail", n: counts?.fail },
    { id: "error", label: "Error", n: counts?.error },
    { id: "blocked", label: "Blocked", n: counts?.blocked },
    { id: "pass", label: "Pass", n: counts?.pass },
    { id: "skipped", label: "Skipped", n: counts?.skipped },
  ];
  const st = useStore.getState();

  return (
    <div className="sidebar">
      <div className="side-head">
        <div className="row" style={{ gap: 4 }}>
          <select className="select sm grow" value={run?.runId ?? ""} onChange={(e) => st.setActiveRun(e.target.value)} title="Run shown">
            {runs.map((r) => <option key={r.runId} value={r.runId}>{runLabel(r)}</option>)}
          </select>
          <HistoryButton />
          <Button size="sm" variant="ghost" icon={<FolderOpen size={14} />} title="Open a saved result (.run.jsonl)" onClick={pickRunFile} />
        </div>
        <div className="chips">
          {chips.filter((c) => c.id === "notpass" || c.id === "all" || (c.n ?? 0) > 0 || filter === c.id).map((c) => (
            <button key={c.id} className={`chip${filter === c.id ? " on" : ""}`} onClick={() => st.setFilter(c.id)}>
              {c.id !== "notpass" && c.id !== "all" ? <StatusIcon status={c.id} size={12} /> : null}
              {c.label}
              <span className="n">{c.n ?? 0}</span>
            </button>
          ))}
        </div>
        <div className="row" style={{ gap: 4 }}>
          <div className="search grow">
            <Search size={14} />
            <input ref={searchRef} className="input sm" placeholder="Search   ( / )" value={search} onChange={(e) => st.setSearch(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Escape") {
                  st.setSearch("");
                  listRef.current?.focus();
                }
              }} />
            {search ? <button className="btn ghost sm icon" style={{ position: "absolute", right: 0, top: 0 }} onClick={() => st.setSearch("")}><X size={12} /></button> : null}
          </div>
          <div className="segmented sm" title="Group by folder or by failure cause">
            <button className={grouping === "tree" ? "on" : ""} onClick={() => st.setGrouping("tree")} title="By folder"><ListTree size={13} /></button>
            <button className={grouping === "groups" ? "on" : ""} onClick={() => st.setGrouping("groups")} title="By failure cause"><Rows3 size={13} /></button>
          </div>
        </div>
        {runs.length > 1 ? (
          <div className="row" style={{ gap: 4 }}>
            <GitCompare size={13} className="faint" />
            <select className="select sm grow" value={compareWith ?? ""} onChange={(e) => st.setCompareWith(e.target.value || undefined)} title="Mark what changed since another run">
              <option value="">Changes since… (off)</option>
              {runs.filter((r) => r.runId !== run?.runId).map((r) => <option key={r.runId} value={r.runId}>{runLabel(r)}</option>)}
            </select>
          </div>
        ) : null}
      </div>
      {run && !run.done ? (
        <div className="progress"><div style={{ width: `${counts && counts.all ? ((counts.all - counts.queued - counts.running) / counts.all) * 100 : 0}%` }} /></div>
      ) : null}
      <div className="side-list" ref={listRef} tabIndex={0} onScroll={(e) => (savedScroll = (e.target as HTMLElement).scrollTop)}>
        {rows.length === 0 ? (
          <div className="empty" style={{ height: "auto", paddingTop: 40 }}>
            <div className="col">
              {filter === "notpass" && run?.done ? <div>Everything passed.</div> : <div>Nothing matches.</div>}
              {filter !== "all" ? <Button size="sm" onClick={() => st.setFilter("all")}>Show all</Button> : null}
            </div>
          </div>
        ) : null}
        <div style={{ height: v.getTotalSize(), position: "relative" }}>
          {v.getVirtualItems().map((vi) => {
            const r = rows[vi.index];
            const style = { position: "absolute" as const, top: 0, left: 0, right: 0, transform: `translateY(${vi.start}px)` };
            if (r.kind === "group") {
              return (
                <div key={r.id} className="group-row" style={style} title={r.label}>
                  <span className="label">{r.label}</span>
                  <span className="faint" style={{ fontWeight: 400 }}>{r.count}</span>
                </div>
              );
            }
            if (r.kind === "hook") {
              const sel = selectedId === r.id;
              return (
                <div key={r.id} className={`tree-row${sel ? " selected" : ""}`} style={{ ...style, paddingLeft: 10 }}
                  onClick={() => st.selectRun({ hook: r.id })}>
                  <StatusIcon status={r.hook.status} size={14} />
                  <SlidersHorizontal size={12} className="faint" />
                  <span className="label">{r.label}</span>
                  <span className="meta">{fmtMs(r.hook.durationMs)}</span>
                </div>
              );
            }
            const sel = selectedId === r.id;
            const tri = r.result?.triage?.label ? TRIAGE.find((t) => t.id === r.result!.triage!.label)?.label ?? r.result.triage.label : null;
            return (
              <div key={r.id} className={`tree-row${sel ? " selected" : ""}`} style={{ ...style, paddingLeft: 10 }}
                onClick={() => st.selectRun({ caseId: r.caseId, step: undefined, tab: st.runSel.tab })}
                title={r.result?.reason ?? undefined}>
                <StatusIcon status={r.state} size={14} />
                <span className="label">
                  {r.key ? <span className="key">{r.key}</span> : null}
                  {r.name !== r.key ? r.name : ""}
                </span>
                <span className="meta">
                  {r.change === "fixed" ? <span className="badge good">fixed</span> : null}
                  {r.change === "new" ? <span className="badge bad">new</span> : null}
                  {tri ? <span className="badge primary">{tri}</span> : r.result?.triage?.note ? <span className="badge">note</span> : null}
                  {r.result?.cleanupFailed ? <span className="badge warn" title="Cleanup did not pass">cleanup</span> : null}
                  {r.result ? fmtMs(r.result.durationMs) : null}
                </span>
              </div>
            );
          })}
        </div>
      </div>
      <div className="side-foot">
        {run ? <span className="ellipsis">{run.done ? (run.summary ? `Finished in ${fmtMs(run.summary.durationMs)}` : "Finished") : "Running…"}</span> : null}
      </div>
    </div>
  );
}

/** Lists saved results (results folder) and opens one. */
function HistoryButton({ label }: { label?: boolean }) {
  const serverFile = useStore((s) => s.serverFile);
  const resultsDir = useStore((s) => s.resultsDir);
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<HistoryEntry[] | null>(null);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const down = (e: MouseEvent) => ref.current && !ref.current.contains(e.target as globalThis.Node) && setOpen(false);
    document.addEventListener("mousedown", down, true);
    return () => document.removeEventListener("mousedown", down, true);
  }, [open]);
  if (!serverFile && !resultsDir) return null;
  const toggle = async () => {
    if (open) return setOpen(false);
    setOpen(true);
    setItems(null);
    try {
      setItems(await historyEntries());
    } catch (e) {
      setItems([]);
      useStore.getState().toast("error", (e as Error).message);
    }
  };
  return (
    <div className="menu-wrap" ref={ref}>
      <Button size="sm" variant="ghost" icon={<History size={14} />} title="Saved results in the results folder" onClick={toggle}>{label ? "History" : undefined}</Button>
      {open ? (
        <div className="menu right" style={{ width: 380, maxHeight: 420, overflow: "auto" }}>
          {items === null ? <div className="menu-head">Loading…</div> : null}
          {items && !items.length ? <div className="menu-head">No saved results yet</div> : null}
          {(items ?? []).slice(0, 60).map((h) => (
            <button key={h.name} className="menu-item" style={{ height: "auto", padding: "6px 10px", flexDirection: "column", alignItems: "flex-start", gap: 2 }}
              onClick={() => { setOpen(false); openHistory(h); }}>
              <span className="ellipsis" style={{ maxWidth: 350 }}>{h.name}</span>
              <span className="faint" style={{ fontSize: 11.5 }}>{fmtTime(h.modified)} · {fmtSize(h.size)}</span>
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}
