import { useMemo } from "react";
import { ChevronRight, CopyPlus, Ellipsis, FilePlus, FolderOpen, FolderPlus, Play, Settings, Trash2 } from "lucide-react";
import { addCase, addFolder, deleteNodes, duplicateNodes, runNodes } from "../actions";
import { openCase, openFolder } from "../nav";
import { useStore } from "../store";
import type { Phase } from "../suiteOps";
import { caseLabel, defaultsChain, find, stepsOf, updateNode, withSteps } from "../suiteOps";
import type { Defaults, Folder, RunPolicy, Step } from "../types";
import { DefaultsForm } from "./SuiteEditor";
import { StepsWorkspace } from "./StepsWorkspace";
import { Button, Check, DropMenu, Empty, Field, StatusIcon } from "./ui";

export function FolderEditor({ id }: { id: string }) {
  const suite = useStore((s) => s.suite)!;
  const sel = useStore((s) => s.sel);
  const runs = useStore((s) => s.runs);
  const loc = useMemo(() => find(suite, id), [suite, id]);
  const latestHooks = useMemo(() => {
    for (const r of runs) {
      const hs = r.hooks.filter((h) => h.folderId === id);
      if (hs.length) return hs;
    }
    return [];
  }, [runs, id]);
  if (!loc || loc.node.type !== "folder") return <Empty>This folder is not in the suite.</Empty>;
  const f = loc.node;

  const edit = (fn: (x: Folder) => Folder, coalesce?: string) =>
    useStore.getState().edit((s) => updateNode(s, id, (n) => (n.type === "folder" ? fn(n) : n)), coalesce ? `${id}:${coalesce}` : undefined);
  const setSteps = (phase: Phase, steps: Step[], coalesce?: string) => edit((x) => withSteps(x, phase, steps), coalesce);

  const statuses: Record<string, string> = {};
  for (const h of latestHooks) for (const s of h.steps ?? []) statuses[`${s.phase}:${s.index}`] = s.status;
  const phase = sel.tab === "settings" ? undefined : sel.phase;
  const cases = (f.items ?? []).filter((n) => n.type === "case");
  const folders = (f.items ?? []).filter((n) => n.type === "folder");
  const where = { parentId: id, index: -1 };

  return (
    <div className="pane">
      <div className="crumbs">
        <FolderOpen size={12} />
        {loc.path.length ? loc.path.map((p) => (
          <span key={p.id} className="row" style={{ gap: 4 }}>
            <button onClick={() => openFolder(p.id)}>{p.name}</button>
            <ChevronRight size={12} />
          </span>
        )) : <span>Suite root</span>}
      </div>
      <div className="head">
        <input className="input bare title-input grow" value={f.name} placeholder="Folder name" onChange={(e) => edit((x) => ({ ...x, name: e.target.value }), "name")} />
        <Check checked={!f.disabled} onChange={(v) => edit((x) => ({ ...x, disabled: v ? undefined : true }))} title="Nothing inside a disabled folder runs">Enabled</Check>
        <Button variant="primary" icon={<Play size={14} />} onClick={() => runNodes([id])} title="Run everything in this folder">Run folder</Button>
        <DropMenu align="right" trigger={({ toggle }) => <Button variant="ghost" icon={<Ellipsis size={16} />} onClick={toggle} title="More" />}
          items={[
            { label: "New test case here", icon: <FilePlus size={14} />, onClick: () => addCase(where) },
            { label: "New folder here", icon: <FolderPlus size={14} />, onClick: () => addFolder(where) },
            { label: "Duplicate folder", icon: <CopyPlus size={14} />, onClick: () => duplicateNodes([id]) },
            { sep: true },
            { label: "Delete folder", icon: <Trash2 size={14} />, danger: true, onClick: () => deleteNodes([id]) },
          ]} />
      </div>
      <StepsWorkspace
        nodeId={id}
        folderId={id}
        inherited={defaultsChain(suite, [...loc.path, f])}
        sections={[
          { phase: "setup", label: "Setup", steps: stepsOf(f, "setup"), hint: "Runs once before the test cases of this folder" },
          { phase: "teardown", label: "Teardown", steps: stepsOf(f, "teardown"), hint: "Runs once after them, even when they fail" },
        ]}
        onSteps={setSteps}
        sel={{ phase, step: sel.step }}
        onSelect={(p, i) => useStore.getState().select(p ? { kind: "folder", id, phase: p, step: i } : { kind: "folder", id, tab: "settings" }, { keepMulti: true })}
        statuses={statuses}
        top={
          <div className={`side-link${phase ? "" : " active"}`} onClick={() => useStore.getState().select({ kind: "folder", id, tab: "settings" }, { keepMulti: true })}>
            <Settings size={14} className="faint" />
            <span>Folder settings</span>
          </div>
        }
        other={
          <div className="form">
            <Field label="Description">
              <textarea className="textarea" rows={2} value={f.description ?? ""} onChange={(e) => edit((x) => ({ ...x, description: e.target.value || undefined }), "desc")} />
            </Field>
            <RunPolicyForm value={f.run} onChange={(r) => edit((x) => ({ ...x, run: r }))} inheritedNote="Empty settings follow the suite." />
            <div className="section-title">Defaults for everything inside</div>
            <DefaultsForm value={f.defaults} onChange={(d: Defaults | undefined) => edit((x) => ({ ...x, defaults: d }), "defaults")} />
            <div className="section-title">Contents</div>
            <div className="card">
              {folders.length + cases.length === 0 ? <div className="card-pad muted">Empty folder.</div> : null}
              <table className="table">
                <tbody>
                  {folders.map((n) => (
                    <tr key={n.id} className="click" onClick={() => openFolder(n.id)}>
                      <td style={{ width: 22 }}><FolderOpen size={14} className="faint" /></td>
                      <td>{n.name}</td>
                      <td className="num faint">{n.type === "folder" ? plural((n.items ?? []).length, "item") : ""}</td>
                    </tr>
                  ))}
                  {cases.map((n) => (
                    <tr key={n.id} className="click" onClick={() => openCase(n.id)}>
                      <td style={{ width: 22 }}><StatusIcon status={lastStatus(runs, n.id)} size={13} /></td>
                      <td className={n.disabled ? "faint" : undefined}>{n.type === "case" ? caseLabel(n) : ""}</td>
                      <td className="num faint">{n.type === "case" ? plural((n.steps ?? []).length, "step") : ""}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        }
      />
    </div>
  );
}

function plural(n: number, word: string): string {
  return `${n} ${word}${n === 1 ? "" : "s"}`;
}

function lastStatus(runs: { cases: Record<string, { status: string }> }[], id: string): string | undefined {
  for (const r of runs) if (r.cases[id]) return r.cases[id].status;
  return "none";
}

export function RunPolicyForm({ value, onChange, inheritedNote }: { value?: RunPolicy; onChange: (r?: RunPolicy) => void; inheritedNote?: string }) {
  const set = (p: Partial<RunPolicy>) => {
    const next: RunPolicy = { ...value, ...p };
    if (!next.cases) delete next.cases;
    if (!next.folders) delete next.folders;
    onChange(Object.keys(next).length ? next : undefined);
  };
  return (
    <div className="grid2">
      <Field label="Test cases of a folder run" hint={inheritedNote}>
        <select className="select" value={value?.cases ?? ""} onChange={(e) => set({ cases: (e.target.value || undefined) as RunPolicy["cases"] })}>
          <option value="">{inheritedNote ? "As above" : "In parallel (default)"}</option>
          <option value="parallel">In parallel, up to the concurrency limit</option>
          <option value="serial">One after another, in order</option>
        </select>
      </Field>
      <Field label="Sub-folders run" hint="Each folder waits for its own setup before its test cases start.">
        <select className="select" value={value?.folders ?? ""} onChange={(e) => set({ folders: (e.target.value || undefined) as RunPolicy["folders"] })}>
          <option value="">{inheritedNote ? "As above" : "One after another (default)"}</option>
          <option value="serial">One after another</option>
          <option value="parallel">At the same time</option>
        </select>
      </Field>
    </div>
  );
}
