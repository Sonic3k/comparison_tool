import {
  ChevronDown, CircleAlert, CircleStop, CornerUpLeft, Download, FilePlus, FolderOpen, Info, Keyboard, Moon, Play, Redo2, Replace, Save,
  SlidersHorizontal, Sun, Undo2, X,
} from "lucide-react";
import {
  chooseResultsDir, confirmDiscard, exportSuite, newSuite, openRunDialog, pickAndOpen, pickRunFile, runNodes, saveSuite, saveSuiteAs,
} from "../actions";
import { canPickFolder } from "../files";
import { useStore } from "../store";
import { FindReplace, IssuesDialog, Shortcuts } from "./Dialogs";
import { Button, DropMenu, useOverlay } from "./ui";
import type { MenuItem } from "./ui";
import type { ReactNode } from "react";

export function showDialog(make: (close: () => void) => ReactNode) {
  let id = 0;
  const close = () => useOverlay.getState().closeDialog(id);
  id = useOverlay.getState().showDialog(make(close));
}

export function Topbar() {
  const suite = useStore((s) => s.suite);
  const fileName = useStore((s) => s.fileName);
  const dirty = useStore((s) => s.dirty);
  const serverFile = useStore((s) => s.serverFile);
  const view = useStore((s) => s.view);
  const env = useStore((s) => s.env);
  const baselineEnv = useStore((s) => s.baselineEnv);
  const runs = useStore((s) => s.runs);
  const issues = useStore((s) => s.issues);
  const notes = useStore((s) => s.notes);
  const multi = useStore((s) => s.multi);
  const theme = useStore((s) => s.theme);
  const past = useStore((s) => s.past.length);
  const future = useStore((s) => s.future.length);
  const resultsDirName = useStore((s) => s.resultsDirName);
  const fromResults = useStore((s) => s.fromResults);
  const st = useStore.getState();
  const live = runs.find((r) => r.source === "live" && !r.done);
  const errors = issues.filter((i) => i.level === "error").length;
  const envNames = (suite?.environments ?? []).map((e) => e.name);

  const fileItems: MenuItem[] = [
    { label: "Open…", icon: <FolderOpen size={14} />, hint: "Ctrl+O", onClick: pickAndOpen, disabled: !!serverFile },
    { label: "New suite", icon: <FilePlus size={14} />, onClick: newSuite, disabled: !!serverFile },
    { sep: true },
    { label: "Save", icon: <Save size={14} />, hint: "Ctrl+S", onClick: saveSuite, disabled: !suite },
    { label: serverFile ? "Save a copy as…" : "Save as…", onClick: saveSuiteAs, disabled: !suite },
    { sep: true },
    { header: "Export" },
    { label: "Excel workbook (.xlsx)", icon: <Download size={14} />, onClick: () => exportSuite("excel"), disabled: !suite },
    { label: "Postman collection", icon: <Download size={14} />, onClick: () => exportSuite("postman"), disabled: !suite },
    { label: "Bruno collection (.zip)", icon: <Download size={14} />, onClick: () => exportSuite("bruno"), disabled: !suite },
    { sep: true },
    { label: "Open run result…", icon: <FolderOpen size={14} />, onClick: pickRunFile },
  ];
  if (canPickFolder && !serverFile) {
    fileItems.push({ label: resultsDirName ? `Results folder: ${resultsDirName}` : "Save runs to a folder…", onClick: chooseResultsDir });
  }
  fileItems.push(
    { sep: true },
    { label: "Find and replace…", icon: <Replace size={14} />, hint: "Ctrl+Shift+F", onClick: () => showDialog((c) => <FindReplace onClose={c} />), disabled: !suite },
    { label: "Keyboard shortcuts", icon: <Keyboard size={14} />, hint: "?", onClick: () => showDialog((c) => <Shortcuts onClose={c} />) },
  );
  if (suite && !serverFile) {
    fileItems.push({ sep: true }, { label: "Close suite", icon: <X size={14} />, onClick: async () => (await confirmDiscard()) && useStore.getState().close() });
  }

  return (
    <header className="topbar">
      <div className="brand">
        <div className="brand-mark">
          <svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M3 4.5h6M3 8h10M3 11.5h5" /></svg>
        </div>
        <span className="brand-name">API Comparison Tools</span>
      </div>
      {suite ? (
        <div className="suite-title" title={serverFile ?? fileName ?? undefined}>
          <span className="suite-name">{suite.name || "Untitled suite"}</span>
          {dirty ? <span className="dirty-dot" title="Unsaved changes" /> : null}
          <span className="brand-file">{fileName ?? "not saved yet"}</span>
        </div>
      ) : null}
      <DropMenu trigger={({ toggle }) => <Button variant="ghost" onClick={toggle}>File <ChevronDown size={13} /></Button>} items={fileItems} />
      {suite ? (
        <>
          <Button variant="ghost" icon={<Save size={15} />} onClick={saveSuite} title={serverFile ? `Save to ${serverFile} (Ctrl+S)` : "Save (Ctrl+S)"} disabled={!dirty} />
          <Button variant="ghost" icon={<Undo2 size={15} />} onClick={st.undo} title="Undo (Ctrl+Z)" disabled={!past} />
          <Button variant="ghost" icon={<Redo2 size={15} />} onClick={st.redo} title="Redo (Ctrl+Shift+Z)" disabled={!future} />
          <span className="sep" />
          <div className="env-pick">
            <label htmlFor="env">Env</label>
            <select id="env" className="select sm" style={{ width: 130 }} value={env ?? ""} onChange={(e) => st.setEnv(e.target.value, baselineEnv)}>
              {envNames.map((n) => <option key={n}>{n}</option>)}
            </select>
            <label htmlFor="base" title="Call this environment too and compare every response">vs</label>
            <select id="base" className="select sm" style={{ width: 130 }} value={baselineEnv ?? ""} onChange={(e) => st.setEnv(env, e.target.value || undefined)}
              title="Compare with a baseline environment">
              <option value="">no compare</option>
              {envNames.filter((n) => n !== env).map((n) => <option key={n}>{n}</option>)}
            </select>
          </div>
          {live ? (
            <Button variant="stop" icon={<CircleStop size={14} />} onClick={() => st.stopRun(live.runId)} title="Stop the run (press again to stop at once)">Stop</Button>
          ) : (
            <span className="row" style={{ gap: 0 }}>
              <Button variant="run" className="split-l" icon={<Play size={14} />} onClick={() => runNodes(multi.length > 1 ? multi : [])}
                title={multi.length > 1 ? `Run the ${multi.length} selected items` : "Run the whole suite"}>
                {multi.length > 1 ? `Run ${multi.length} selected` : "Run all"}
              </Button>
              <DropMenu align="right" trigger={({ toggle }) => <Button variant="run" className="split-r" icon={<ChevronDown size={14} />} onClick={toggle} title="Run options" />}
                items={[
                  { label: "Run whole suite", icon: <Play size={14} />, onClick: () => runNodes([]) },
                  { label: `Run selected (${multi.length})`, onClick: () => runNodes(multi), disabled: !multi.length },
                  { label: "Run with options…", icon: <SlidersHorizontal size={14} />, onClick: () => openRunDialog() },
                ]} />
            </span>
          )}
        </>
      ) : null}
      <span className="right" />
      {suite && issues.length ? (
        <Button variant="ghost" icon={<CircleAlert size={15} className={errors ? "st-fail" : "st-error"} />} onClick={() => showDialog((c) => <IssuesDialog onClose={c} />)}
          title="Problems found in the suite">{issues.length}</Button>
      ) : suite && notes.length ? (
        <Button variant="ghost" icon={<Info size={15} />} onClick={() => showDialog((c) => <IssuesDialog onClose={c} />)} title="Conversion notes" />
      ) : null}
      {view === "edit" && fromResults ? (
        <button className="back-pill" onClick={() => st.setView("runs")} title={`Back to the results of ${fromResults.label} (Alt+2)`}>
          <CornerUpLeft size={13} />Back to results
        </button>
      ) : null}
      <div className="segmented">
        <button className={view === "edit" ? "on" : ""} onClick={() => st.setView("edit")} title="Alt+1">Edit</button>
        <button className={view === "runs" ? "on" : ""} onClick={() => st.setView("runs")} title="Alt+2">
          Runs{runs.length ? <span className="faint">{runs.length}</span> : null}
          {live ? <span className="status-dot running" /> : null}
        </button>
      </div>
      <Button variant="ghost" icon={theme === "dark" ? <Sun size={15} /> : <Moon size={15} />} onClick={() => st.setTheme(theme === "dark" ? "light" : "dark")} title="Light or dark" />
    </header>
  );
}
