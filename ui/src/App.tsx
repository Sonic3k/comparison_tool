import { useEffect, useState } from "react";
import { X } from "lucide-react";
import { api } from "./api";
import { openFile, pickAndOpen, rerunCase, restoreResultsDir, saveSuite, saveSuiteAs } from "./actions";
import { usePrefNumber } from "./hooks";
import { openCase, openFolder } from "./nav";
import { applyHash, startRouter } from "./router";
import { runRows } from "./runRows";
import { useStore } from "./store";
import type { Phase } from "./suiteOps";
import { CaseEditor } from "./components/CaseEditor";
import { FindReplace, Shortcuts } from "./components/Dialogs";
import { FolderEditor } from "./components/FolderEditor";
import { RunMain, defaultStep } from "./components/RunView";
import { RunsSidebar, TRIAGE } from "./components/RunsSidebar";
import { SuiteEditor } from "./components/SuiteEditor";
import { Topbar, showDialog } from "./components/Topbar";
import { Tree } from "./components/Tree";
import { Welcome } from "./components/Welcome";
import { Button, ContextMenuHost, DialogHost, Splitter, isTyping, useOverlay } from "./components/ui";

export function App() {
  const suite = useStore((s) => s.suite);
  const view = useStore((s) => s.view);
  const theme = useStore((s) => s.theme);
  const [side, setSide] = usePrefNumber("sideWidth", 320);
  const [ready, setReady] = useState(false);
  const [dragging, setDragging] = useState(false);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);

  const suiteName = suite?.name;
  useEffect(() => {
    document.title = suiteName ? `${suiteName} - API Comparison Tools` : "API Comparison Tools";
  }, [suiteName]);

  useEffect(() => {
    const stop = startRouter();
    (async () => {
      try {
        const [info, opened] = await Promise.all([api.info(), api.startupSuite()]);
        if (opened) {
          useStore.getState().load(opened.suite, {
            fileName: opened.fileName, serverFile: info.file?.path ?? opened.fileName, format: opened.format, notes: opened.notes, issues: opened.issues,
          });
          if (opened.format === "v1") {
            useStore.getState().toast("info", "This suite was converted from the old format. Save replaces the file with the new format and keeps a .bak copy.");
          }
        } else {
          await restoreResultsDir();
        }
      } catch (e) {
        useStore.getState().toast("error", "Could not reach the tool's server: " + (e as Error).message);
      }
      setReady(true);
      applyHash();
    })();
    return stop;
  }, []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => globalKey(e);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  useEffect(() => {
    let depth = 0;
    const hasFiles = (e: DragEvent) => !!e.dataTransfer && Array.from(e.dataTransfer.types).includes("Files");
    const over = (e: DragEvent) => {
      if (hasFiles(e)) e.preventDefault();
    };
    const enter = (e: DragEvent) => {
      if (!hasFiles(e)) return;
      depth++;
      setDragging(true);
    };
    const leave = (e: DragEvent) => {
      if (!hasFiles(e)) return;
      depth = Math.max(0, depth - 1);
      if (!depth) setDragging(false);
    };
    const drop = (e: DragEvent) => {
      if (!hasFiles(e)) return;
      e.preventDefault();
      depth = 0;
      setDragging(false);
      const f = e.dataTransfer?.files?.[0];
      if (f) openFile(f);
    };
    window.addEventListener("dragover", over);
    window.addEventListener("dragenter", enter);
    window.addEventListener("dragleave", leave);
    window.addEventListener("drop", drop);
    return () => {
      window.removeEventListener("dragover", over);
      window.removeEventListener("dragenter", enter);
      window.removeEventListener("dragleave", leave);
      window.removeEventListener("drop", drop);
    };
  }, []);

  useEffect(() => {
    const h = (e: BeforeUnloadEvent) => {
      const st = useStore.getState();
      const busy = st.runs.some((r) => r.source === "live" && !r.done);
      const triage = st.runs.some((r) => r.triageChanged);
      if ((st.suite && st.dirty) || busy || triage) {
        e.preventDefault();
        e.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", h);
    return () => window.removeEventListener("beforeunload", h);
  }, []);

  const showWelcome = ready && !suite && view === "edit";
  return (
    <div className="app" style={{ ["--side-w" as string]: `${side}px` }}>
      <Topbar />
      {!ready ? <div /> : showWelcome ? (
        <div style={{ minHeight: 0, overflow: "auto" }}><Welcome /></div>
      ) : (
        <div className="body">
          {view === "edit" ? <Tree /> : <RunsSidebar />}
          <Splitter value={side} onChange={setSide} min={220} max={Math.max(260, Math.min(720, window.innerWidth - 480))} />
          <main className="main">{view === "edit" ? <Editor /> : <RunMain />}</main>
        </div>
      )}
      <Toasts />
      <DialogHost />
      <ContextMenuHost />
      {dragging ? <div className="dropzone-overlay"><div>Drop to open</div></div> : null}
    </div>
  );
}

function Editor() {
  const sel = useStore((s) => s.sel);
  if (sel.kind === "folder" && sel.id) return <FolderEditor key={sel.id} id={sel.id} />;
  if (sel.kind === "case" && sel.id) return <CaseEditor key={sel.id} id={sel.id} />;
  return <SuiteEditor />;
}

function Toasts() {
  const toasts = useStore((s) => s.toasts);
  const drop = useStore((s) => s.dropToast);
  return (
    <div className="toasts" aria-live="polite">
      {toasts.map((t) => (
        <div key={t.id} className={`toast ${t.kind}`}>
          <div className="t">{t.text}</div>
          {t.action ? <Button size="sm" onClick={() => { t.action!.run(); drop(t.id); }}>{t.action.label}</Button> : null}
          <Button size="sm" variant="ghost" icon={<X size={13} />} onClick={() => drop(t.id)} title="Dismiss" />
        </div>
      ))}
    </div>
  );
}

function globalKey(e: KeyboardEvent) {
  const st = useStore.getState();
  const mod = e.ctrlKey || e.metaKey;
  const k = e.key.toLowerCase();
  if (mod && k === "s") {
    e.preventDefault();
    if (st.suite) (e.shiftKey ? saveSuiteAs : saveSuite)();
    return;
  }
  if (mod && k === "o") {
    e.preventDefault();
    if (!st.serverFile) pickAndOpen();
    return;
  }
  if (mod && e.shiftKey && k === "f") {
    e.preventDefault();
    if (st.suite) showDialog((c) => <FindReplace onClose={c} />);
    return;
  }
  if (e.altKey && (e.key === "1" || e.key === "2")) {
    e.preventDefault();
    st.setView(e.key === "1" ? "edit" : "runs");
    return;
  }
  if (isTyping(e.target) || useOverlay.getState().dialogs.length || useOverlay.getState().menu) return;
  if (mod && k === "z") {
    e.preventDefault();
    if (e.shiftKey) st.redo();
    else st.undo();
    return;
  }
  if (mod && k === "y") {
    e.preventDefault();
    st.redo();
    return;
  }
  if (mod || e.altKey) return;
  if (e.key === "/") {
    e.preventDefault();
    window.dispatchEvent(new Event("ct:search"));
    return;
  }
  if (e.key === "?") {
    showDialog((c) => <Shortcuts onClose={c} />);
    return;
  }
  if (st.view === "runs") runsKey(e);
}

function runsKey(e: KeyboardEvent) {
  const st = useStore.getState();
  const run = st.runs.find((r) => r.runId === st.activeRun);
  if (!run) return;
  const rows = runRows({ run, suite: st.suite, filter: st.filter, search: st.search, grouping: st.grouping, older: st.runs.find((r) => r.runId === st.compareWith) });
  const items = rows.filter((r) => r.kind === "case" || r.kind === "hook");
  const curId = st.runSel.caseId ? `c:${st.runSel.caseId}` : st.runSel.hook;
  const i = items.findIndex((r) => r.id === curId);
  const go = (n: number) => {
    const r = items[n];
    if (!r) return;
    e.preventDefault();
    if (r.kind === "case") st.selectRun({ caseId: r.caseId, tab: st.runSel.tab });
    else if (r.kind === "hook") st.selectRun({ hook: r.id, tab: st.runSel.tab });
  };
  const c = st.runSel.caseId ? run.cases[st.runSel.caseId] : undefined;
  const hook = st.runSel.hook ? run.hooks.find((h) => `hook:${h.folderId ?? "suite"}:${h.hook}` === st.runSel.hook) : undefined;
  const steps = c?.steps ?? hook?.steps ?? [];
  const curStep = st.runSel.step ?? defaultStep(steps);
  switch (e.key) {
    case "j":
      return go(i < 0 ? 0 : Math.min(items.length - 1, i + 1));
    case "k":
      return go(i < 0 ? 0 : Math.max(0, i - 1));
    case "]":
      if (steps.length) {
        e.preventDefault();
        st.selectRun({ ...st.runSel, step: Math.min(steps.length - 1, curStep + 1) });
      }
      return;
    case "[":
      if (steps.length) {
        e.preventDefault();
        st.selectRun({ ...st.runSel, step: Math.max(0, curStep - 1) });
      }
      return;
    case "e":
      if (c) {
        const s = steps[curStep];
        if (s) openCase(c.caseId, s.phase as Phase, s.index);
        else openCase(c.caseId);
      } else if (hook) {
        const s = steps[curStep];
        if (hook.folderId) openFolder(hook.folderId, hook.hook as Phase, s?.index ?? 0);
        else st.select({ kind: "suite", tab: "hooks", phase: hook.hook as Phase, step: s?.index ?? 0 });
      }
      return;
    case "r":
      if (c) rerunCase(run, c.caseId);
      return;
    case "0":
      if (c) st.setTriage(run.runId, c.caseId, undefined, c.triage?.note);
      return;
    default: {
      const t = TRIAGE.find((x) => x.key === e.key);
      if (t && c) st.setTriage(run.runId, c.caseId, c.triage?.label === t.id ? undefined : t.id, c.triage?.note);
    }
  }
}
