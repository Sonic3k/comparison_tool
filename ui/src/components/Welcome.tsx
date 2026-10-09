import { useEffect, useState } from "react";
import { FilePlus, FileText, FolderOpen, History, RotateCcw } from "lucide-react";
import type { Draft } from "../actions";
import { loadDraft, newSuite, openFile, openRecent, pickAndOpen, pickRunFile, recentFiles, restoreDraft } from "../actions";
import { fmtTime } from "../runs";
import { Button } from "./ui";

export function Welcome() {
  const [over, setOver] = useState(false);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [recent, setRecent] = useState<{ name: string; handle: unknown; at: number }[]>([]);
  useEffect(() => {
    loadDraft().then(setDraft);
    recentFiles().then(setRecent);
  }, []);
  return (
    <div className="welcome">
      <div className="welcome-card">
        <div>
          <h1>API Comparison Tools</h1>
          <div className="muted">API test suites organised by test case: run them, compare two environments, and triage what failed.</div>
        </div>
        <div className={`drop${over ? " over" : ""}`}
          onDragOver={(e) => {
            if (!e.dataTransfer.types.includes("Files")) return;
            e.preventDefault();
            setOver(true);
          }}
          onDragLeave={() => setOver(false)}
          onDrop={(e) => {
            e.preventDefault();
            e.stopPropagation();
            setOver(false);
            const f = e.dataTransfer.files?.[0];
            if (f) openFile(f);
          }}>
          <FolderOpen size={26} className="faint" />
          <div style={{ fontWeight: 600 }}>Drop a suite or a run result here</div>
          <div className="faint" style={{ fontSize: 12 }}>.suite.json · old suite .json (converted on open) · .xlsx · .run.jsonl</div>
          <div className="row" style={{ marginTop: 6 }}>
            <Button variant="primary" icon={<FolderOpen size={14} />} onClick={pickAndOpen}>Open…</Button>
            <Button icon={<FilePlus size={14} />} onClick={newSuite}>New suite</Button>
            <Button icon={<History size={14} />} onClick={pickRunFile}>Open run result…</Button>
          </div>
        </div>
        {draft ? (
          <div className="card card-pad row">
            <RotateCcw size={15} className="faint" />
            <div className="grow">
              <div>Unsaved changes to <b>{draft.suite.name}</b>{draft.fileName ? <> ({draft.fileName})</> : null}</div>
              <div className="faint" style={{ fontSize: 12 }}>kept by this browser {fmtTime(new Date(draft.at).toISOString())}</div>
            </div>
            <Button onClick={() => restoreDraft(draft)}>Restore</Button>
          </div>
        ) : null}
        {recent.length ? (
          <div>
            <div className="section-title" style={{ marginBottom: 4 }}>Recent</div>
            <div className="recent">
              {recent.map((r) => (
                <button key={r.name} onClick={() => openRecent(r as never)}>
                  <FileText size={14} className="faint" />
                  <span className="grow ellipsis">{r.name}</span>
                  <span className="faint" style={{ fontSize: 12 }}>{fmtTime(new Date(r.at).toISOString())}</span>
                </button>
              ))}
            </div>
          </div>
        ) : null}
        <div className="note">
          <div>Without the browser: <code>java -jar comparison-tool.jar run my.suite.json --env dev</code> writes results to <code>results/</code> next to the suite.</div>
          <div style={{ marginTop: 4 }}>With a file: <code>java -jar comparison-tool.jar my.suite.json</code> opens it here; Save writes back to that file.</div>
        </div>
      </div>
    </div>
  );
}
