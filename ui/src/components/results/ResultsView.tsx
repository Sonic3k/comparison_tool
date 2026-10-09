import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { FolderOpen, History, Play } from "lucide-react";
import { historyEntries, openHistory, openRunDialog, pickRunFile } from "../../actions";
import type { HistoryEntry } from "../../actions";
import { fmtSize, fmtTime } from "../../runs";
import { useResults } from "../../results/compute";
import { loadPast } from "../../results/history";
import { useStore } from "../../store";
import { Button, Splitter } from "../ui";
import { BatchBar, RunBar, Toolbar } from "./Bars";
import { DetailPanel } from "./DetailPanel";
import { ResultsTable } from "./ResultsTable";

/**
 * The results screen: the run's totals and what changed since the run before, one grouped list of every test case,
 * and the details of the one picked below it (or beside it), so the list stays in view while going through them.
 */
export function ResultsView() {
  const data = useResults();
  const runs = useStore((s) => s.runs);
  const runSel = useStore((s) => s.runSel);
  const dock = useStore((s) => s.dock);
  const size = useStore((s) => s.detailSize);
  const maximized = useStore((s) => s.maximized);
  const pastRuns = useStore((s) => s.pastRuns);
  const bodyRef = useRef<HTMLDivElement>(null);
  const [box, setBox] = useState({ w: 1200, h: 600 });
  const run = data.run;
  const suiteName = run?.header.suite;

  useLayoutEffect(() => {
    const el = bodyRef.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setBox({ w: el.clientWidth, h: el.clientHeight }));
    ro.observe(el);
    setBox({ w: el.clientWidth, h: el.clientHeight });
    return () => ro.disconnect();
  }, [run?.runId]);

  useEffect(() => {
    if (suiteName) loadPast(suiteName);
  }, [suiteName, pastRuns]);

  if (!runs.length || !run) return <NoRuns />;
  const open = !!(runSel.caseId || runSel.hook);
  const horizontal = dock === "right";
  const total = horizontal ? box.w : box.h;
  const min = horizontal ? 420 : 200;
  const max = Math.max(min, total - (horizontal ? 380 : 140));
  const current = Math.max(min, Math.min(max, size || Math.round(total * (horizontal ? 0.55 : 0.55))));

  return (
    <div className="results">
      <RunBar data={data} />
      <Toolbar data={data} />
      <BatchBar data={data} />
      <div className={`res-body ${horizontal ? "dock-right" : "dock-bottom"}${open && maximized ? " max" : ""}`} ref={bodyRef}>
        {!(open && maximized) ? <ResultsTable data={data} /> : null}
        {open ? (
          <>
            {!maximized ? (
              <Splitter axis={horizontal ? "x" : "y"} invert value={current} min={min} max={max} onChange={(v) => useStore.getState().setDetailSize(v)}
                className={horizontal ? "splitter dsplit" : "vsplit dsplit"} />
            ) : null}
            <div className="detail-wrap" style={maximized ? { flex: 1 } : horizontal ? { width: current } : { height: current }}>
              <DetailPanel data={data} />
            </div>
          </>
        ) : null}
      </div>
    </div>
  );
}

function NoRuns() {
  const suite = useStore((s) => s.suite);
  const serverFile = useStore((s) => s.serverFile);
  const resultsDir = useStore((s) => s.resultsDir);
  const [saved, setSaved] = useState<HistoryEntry[] | null>(null);
  useEffect(() => {
    if (!serverFile && !resultsDir) return;
    historyEntries().then(setSaved).catch(() => setSaved([]));
  }, [serverFile, resultsDir]);
  return (
    <div className="results">
      <div className="empty">
        <div className="col" style={{ maxWidth: 520, width: "100%" }}>
          <div style={{ fontSize: 15, fontWeight: 600, color: "var(--text)" }}>No run open</div>
          <div className="muted">Run the suite, a folder or a test case to see its results here, or open a result saved earlier.</div>
          <div className="row" style={{ justifyContent: "center" }}>
            {suite ? <Button variant="run" icon={<Play size={14} />} onClick={() => openRunDialog("all")}>Run…</Button> : null}
            <Button icon={<FolderOpen size={14} />} onClick={pickRunFile}>Open a result file…</Button>
          </div>
          {saved?.length ? (
            <div className="saved-list">
              <div className="sub-title">Saved results</div>
              {saved.slice(0, 10).map((h) => (
                <button key={h.name} className="saved-item" onClick={() => openHistory(h)} title={h.name}>
                  <History size={14} className="faint" />
                  <span className="grow ellipsis">{h.name.replace(/\.run\.jsonl$/, "")}</span>
                  <span className="faint small">{fmtTime(h.modified)} · {fmtSize(h.size)}</span>
                </button>
              ))}
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
}
