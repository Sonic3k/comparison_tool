import { useMemo } from "react";
import { ChevronRight, Copy, CopyPlus, Ellipsis, FileText, Info, Play, Trash2 } from "lucide-react";
import { duplicateNodes, deleteNodes, runNodes } from "../actions";
import { openFolder } from "../nav";
import { latestCaseResult, useStore } from "../store";
import type { Phase } from "../suiteOps";
import { allCases, caseLabel, defaultsChain, find, stepsOf, updateNode, withSteps } from "../suiteOps";
import type { Step, TestCase } from "../types";
import { fmtMs, fmtTime } from "../runs";
import { StepsWorkspace } from "./StepsWorkspace";
import { Button, Check, DropMenu, Empty, Field, StatusPill, TagInput, copyText } from "./ui";

export function CaseEditor({ id }: { id: string }) {
  const suite = useStore((s) => s.suite)!;
  const sel = useStore((s) => s.sel);
  const runs = useStore((s) => s.runs);
  const issues = useStore((s) => s.issues);
  const loc = useMemo(() => find(suite, id), [suite, id]);
  const allTags = useMemo(() => [...new Set(allCases(suite).flatMap((c) => c.tc.tags ?? []))].sort(), [suite]);
  const latest = useMemo(() => latestCaseResult(runs, id), [runs, id]);
  if (!loc || loc.node.type !== "case") return <Empty>This test case is not in the suite.</Empty>;
  const tc = loc.node;
  const folder = loc.parent;
  const siblings = (folder ? folder.items : suite.items) ?? [];
  const siblingKeys = siblings.filter((n): n is TestCase => n.type === "case" && n.id !== tc.id).map((c) => c.key).filter(Boolean);

  const edit = (fn: (t: TestCase) => TestCase, coalesce?: string) =>
    useStore.getState().edit((s) => updateNode(s, id, (n) => (n.type === "case" ? fn(n) : n)), coalesce ? `${id}:${coalesce}` : undefined);
  const setSteps = (phase: Phase, steps: Step[], coalesce?: string) => edit((t) => withSteps(t, phase, steps), coalesce);

  const statuses: Record<string, string> = {};
  for (const s of latest?.result.steps ?? []) statuses[`${s.phase}:${s.index}`] = s.status;

  const phase = sel.tab === "details" ? undefined : sel.phase;
  const keyIssue = issues.find((i) => i.nodeId === id && i.section === "details");
  const disabledByFolder = loc.path.some((f) => f.disabled);

  return (
    <div className="pane">
      <div className="crumbs">
        <FileText size={12} />
        {loc.path.length ? loc.path.map((f) => (
          <span key={f.id} className="row" style={{ gap: 4 }}>
            <button onClick={() => openFolder(f.id)}>{f.name}</button>
            <ChevronRight size={12} />
          </span>
        )) : <span>Suite root</span>}
      </div>
      <div className="head">
        <input className={`input key-input${keyIssue ? " invalid" : ""}`} style={{ width: Math.min(280, Math.max(110, tc.key.length * 7.6 + 26)) }} value={tc.key} placeholder="KEY" title={keyIssue?.message ?? "Test case key (used in reports and --case)"}
          onChange={(e) => edit((t) => ({ ...t, key: e.target.value }), "key")} />
        <input className="input bare title-input grow" value={tc.name} placeholder="Test case name" onChange={(e) => edit((t) => ({ ...t, name: e.target.value }), "name")} />
        <Check checked={!tc.disabled} onChange={(v) => edit((t) => ({ ...t, disabled: v ? undefined : true }))} title="Disabled test cases do not run">Enabled</Check>
        <Button variant="run" icon={<Play size={14} />} onClick={() => runNodes([id])} title="Run this test case (and the ones it depends on)">Run</Button>
        <DropMenu align="right" trigger={({ toggle }) => <Button variant="ghost" icon={<Ellipsis size={16} />} onClick={toggle} title="More" />}
          items={[
            { label: "Duplicate", icon: <CopyPlus size={14} />, hint: "Ctrl+D", onClick: () => duplicateNodes([id]) },
            { label: "Copy key", icon: <Copy size={14} />, onClick: () => copyText(tc.key) },
            { sep: true },
            { label: "Delete", icon: <Trash2 size={14} />, danger: true, onClick: () => deleteNodes([id]) },
          ]} />
      </div>
      {disabledByFolder ? <div className="note warn" style={{ margin: "8px 20px 0" }}>A folder above this test case is disabled, so it does not run.</div> : null}
      <StepsWorkspace
        nodeId={id}
        folderId={folder?.id}
        inherited={defaultsChain(suite, loc.path)}
        sections={[
          { phase: "pre", label: "Preconditions", steps: stepsOf(tc, "pre"), hint: "Run before the steps; a required one that fails blocks the test case" },
          { phase: "step", label: "Steps", steps: stepsOf(tc, "step"), hint: "No steps yet" },
          { phase: "cleanup", label: "Cleanup", steps: stepsOf(tc, "cleanup"), hint: "Always runs at the end, even after a failure" },
        ]}
        onSteps={setSteps}
        sel={{ phase, step: sel.step }}
        onSelect={(p, i) => useStore.getState().select(p ? { kind: "case", id, phase: p, step: i } : { kind: "case", id, tab: "details" }, { keepMulti: true })}
        statuses={statuses}
        top={
          <div className={`side-link${phase ? "" : " active"}`} onClick={() => useStore.getState().select({ kind: "case", id, tab: "details" }, { keepMulti: true })}>
            <Info size={14} className="faint" />
            <span>Details</span>
            {latest ? <span className="right"><StatusPill status={latest.result.status} /></span> : null}
          </div>
        }
        other={
          <div className="form">
            {latest ? (
              <div className="card card-pad col" style={{ gap: 6 }}>
                <div className="row">
                  <StatusPill status={latest.result.status} />
                  <span className="muted">Last run {fmtTime(latest.result.startedAt ?? latest.run.header.startedAt)} on {latest.run.header.env} · {fmtMs(latest.result.durationMs)}</span>
                  <Button size="sm" className="right" onClick={() => {
                    useStore.getState().setActiveRun(latest.run.runId);
                    useStore.getState().selectRun({ caseId: id });
                  }}>Open result</Button>
                </div>
                {latest.result.reason ? <div className="muted">{latest.result.reason}</div> : null}
              </div>
            ) : null}
            <Field label="Description">
              <textarea className="textarea" rows={3} value={tc.description ?? ""} onChange={(e) => edit((t) => ({ ...t, description: e.target.value || undefined }), "desc")} />
            </Field>
            <Field label="Tags" hint="Run by tag from the Run menu or with --tag on the command line.">
              <TagInput values={tc.tags} onChange={(v) => edit((t) => ({ ...t, tags: v.length ? v : undefined }))} suggestions={allTags} placeholder="smoke, regression…" />
            </Field>
            <Field label="Runs after (test cases of the same folder)" hint="The test case waits for these and is blocked when one of them does not pass. Values they share with folder scope become available here.">
              <TagInput values={tc.dependsOn} onChange={(v) => edit((t) => ({ ...t, dependsOn: v.length ? v : undefined }))} suggestions={siblingKeys} mono placeholder="key of another test case" />
            </Field>
            <Check checked={!!tc.stopOnFailure} onChange={(v) => edit((t) => ({ ...t, stopOnFailure: v || undefined }))}>
              Stop at the first failing step (cleanup still runs)
            </Check>
            {issues.filter((i) => i.nodeId === id && i.section === "details").map((i, n) => (
              <div key={n} className={`note ${i.level === "error" ? "bad" : "warn"}`}>{i.message}</div>
            ))}
            <div className="faint" style={{ fontSize: 12 }}>
              {caseLabel(tc)} · id <span className="mono">{tc.id}</span>
            </div>
          </div>
        }
      />
    </div>
  );
}
