import { useEffect, useState } from "react";
import { X } from "lucide-react";
import { api } from "./api";
import { openFile, pickAndOpen, restoreResultsDir, saveSuite, saveSuiteAs } from "./actions";
import { usePrefNumber } from "./hooks";
import { applyHash, startRouter } from "./router";
import { resultsKey } from "./results/keys";
import { useStore } from "./store";
import { CaseEditor } from "./components/CaseEditor";
import { FindReplace, Shortcuts } from "./components/Dialogs";
import { FolderEditor } from "./components/FolderEditor";
import { ResultsView } from "./components/results/ResultsView";
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
      const busy = st.runs.some((r) => (r.source === "live" && !r.done) || r.rerun);
      const triage = st.runs.some((r) => r.dirty);
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
      ) : view === "runs" ? (
        <ResultsView />
      ) : (
        <div className="body">
          <Tree />
          <Splitter value={side} onChange={setSide} min={220} max={Math.max(260, Math.min(720, window.innerWidth - 480))} />
          <main className="main"><Editor /></main>
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
  if (st.view === "runs") resultsKey(e);
}
