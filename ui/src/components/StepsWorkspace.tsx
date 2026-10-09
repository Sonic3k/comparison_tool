import { useState } from "react";
import type { ReactNode } from "react";
import { ArrowDown, ArrowUp, ClipboardPaste, CopyPlus, Plus, Power, Trash2 } from "lucide-react";
import type { InheritedDefaults, Phase } from "../suiteOps";
import { PHASE_LABEL, emptyStep } from "../suiteOps";
import type { Step } from "../types";
import { useStore } from "../store";
import { CurlImport } from "./Dialogs";
import { StepEditor } from "./StepEditor";
import { Button, Empty, Method, StatusIcon, openContextMenu, useOverlay } from "./ui";
import type { MenuItem } from "./ui";

export interface StepSection {
  phase: Phase;
  label?: string;
  steps: Step[];
  hint?: string;
}

interface Props {
  nodeId?: string;
  folderId?: string;
  inherited: InheritedDefaults[];
  sections: StepSection[];
  onSteps: (phase: Phase, steps: Step[], coalesce?: string) => void;
  sel: { phase?: Phase; step?: number };
  onSelect: (phase?: Phase, step?: number) => void;
  top?: ReactNode;
  other: ReactNode;
  statuses?: Record<string, string>;
}

export function StepsWorkspace({ nodeId, folderId, inherited, sections, onSteps, sel, onSelect, top, other, statuses }: Props) {
  const [drop, setDrop] = useState<{ phase: Phase; index: number; after: boolean } | null>(null);
  const current = sections.find((s) => s.phase === sel.phase);
  const index = sel.step ?? 0;
  const step = current?.steps[index];

  const update = (phase: Phase, fn: (list: Step[]) => Step[], coalesce?: string) => {
    const sec = sections.find((s) => s.phase === phase);
    if (sec) onSteps(phase, fn(sec.steps), coalesce);
  };
  const add = (phase: Phase, s: Step = phase === "pre" ? { ...emptyStep(), options: { required: true } } : emptyStep()) => {
    const sec = sections.find((x) => x.phase === phase)!;
    update(phase, (l) => [...l, s]);
    onSelect(phase, sec.steps.length);
  };
  const remove = (phase: Phase, i: number) => {
    update(phase, (l) => l.filter((_, j) => j !== i));
    useStore.getState().toast("info", "Step deleted", { label: "Undo", run: () => useStore.getState().undo() });
    const sec = sections.find((x) => x.phase === phase)!;
    if (sel.phase === phase) {
      if (sec.steps.length <= 1) onSelect(undefined, undefined);
      else onSelect(phase, Math.max(0, Math.min(i, sec.steps.length - 2)));
    }
  };
  const duplicate = (phase: Phase, i: number) => {
    update(phase, (l) => [...l.slice(0, i + 1), { ...structuredClone(l[i]), name: `${l[i].name} (copy)` }, ...l.slice(i + 1)]);
    onSelect(phase, i + 1);
  };
  const move = (phase: Phase, from: number, to: number) => {
    update(phase, (l) => {
      if (to < 0 || to >= l.length || from === to) return l;
      const next = [...l];
      const [x] = next.splice(from, 1);
      next.splice(to, 0, x);
      return next;
    });
    if (sel.phase === phase && sel.step === from) onSelect(phase, to);
  };
  const moveTo = (from: Phase, i: number, to: Phase) => {
    const src = sections.find((x) => x.phase === from)!;
    const s = src.steps[i];
    const dst = sections.find((x) => x.phase === to)!;
    onSteps(from, src.steps.filter((_, j) => j !== i));
    onSteps(to, [...dst.steps, s]);
    onSelect(to, dst.steps.length);
  };
  const fromCurl = (phase: Phase) => {
    let id = 0;
    id = useOverlay.getState().showDialog(
      <CurlImport onClose={() => useOverlay.getState().closeDialog(id)} onStep={(s) => {
        add(phase, { ...s, name: s.name || `${s.method} ${s.url.replace(/^https?:\/\/[^/]+/, "")}`.slice(0, 80) });
        useOverlay.getState().closeDialog(id);
      }} />,
    );
  };

  const menuFor = (phase: Phase, i: number, s: Step): MenuItem[] => {
    const items: MenuItem[] = [
      { label: "Duplicate", icon: <CopyPlus size={14} />, onClick: () => duplicate(phase, i) },
      { label: "Move up", icon: <ArrowUp size={14} />, onClick: () => move(phase, i, i - 1), disabled: i === 0 },
      { label: "Move down", icon: <ArrowDown size={14} />, onClick: () => move(phase, i, i + 1), disabled: i === sections.find((x) => x.phase === phase)!.steps.length - 1 },
    ];
    for (const other of sections) {
      if (other.phase !== phase) items.push({ label: `Move to ${other.label ?? PHASE_LABEL[other.phase]}`, onClick: () => moveTo(phase, i, other.phase) });
    }
    items.push(
      { label: s.disabled ? "Enable" : "Disable", icon: <Power size={14} />, onClick: () => update(phase, (l) => l.map((x, j) => (j === i ? { ...x, disabled: x.disabled ? undefined : true } : x))) },
      { sep: true },
      { label: "Delete", icon: <Trash2 size={14} />, danger: true, onClick: () => remove(phase, i) },
    );
    return items;
  };

  return (
    <div className="work">
      <div className="steps-col">
        {top}
        {sections.map((sec) => (
          <div className="steps-section" key={sec.phase}>
            <div className="steps-section-head" title={sec.hint}
              onContextMenu={(e) => openContextMenu(e, [
                { label: "Add a step", icon: <Plus size={14} />, onClick: () => add(sec.phase) },
                { label: "Add from cURL…", icon: <ClipboardPaste size={14} />, onClick: () => fromCurl(sec.phase) },
              ])}>
              <span>{sec.label ?? PHASE_LABEL[sec.phase]}</span>
              {sec.steps.length ? <span className="faint" style={{ fontWeight: 400 }}>{sec.steps.length}</span> : null}
              <Button size="sm" variant="ghost" icon={<ClipboardPaste size={13} />} title="Add from cURL" onClick={() => fromCurl(sec.phase)} className="right" />
              <Button size="sm" variant="ghost" icon={<Plus size={14} />} title={`Add a step to ${sec.label ?? PHASE_LABEL[sec.phase]}`} onClick={() => add(sec.phase)} style={{ marginLeft: 0 }} />
            </div>
            {sec.steps.length === 0 ? <div className="steps-empty">{sec.hint ?? "None"}</div> : null}
            {sec.steps.map((s, i) => {
              const active = sel.phase === sec.phase && sel.step === i;
              const st = statuses?.[`${sec.phase}:${i}`];
              const dropCls = drop && drop.phase === sec.phase && drop.index === i ? (drop.after ? " drop-after" : " drop-before") : "";
              return (
                <div key={i} className={`step-item${active ? " active" : ""}${s.disabled ? " off" : ""}${dropCls}`}
                  draggable
                  onClick={() => onSelect(sec.phase, i)}
                  onContextMenu={(e) => openContextMenu(e, menuFor(sec.phase, i, s))}
                  onDragStart={(e) => {
                    e.dataTransfer.setData("application/x-ct-step", JSON.stringify({ phase: sec.phase, index: i }));
                    e.dataTransfer.effectAllowed = "move";
                  }}
                  onDragOver={(e) => {
                    if (!e.dataTransfer.types.includes("application/x-ct-step")) return;
                    e.preventDefault();
                    const r = e.currentTarget.getBoundingClientRect();
                    setDrop({ phase: sec.phase, index: i, after: e.clientY > r.top + r.height / 2 });
                  }}
                  onDragLeave={() => setDrop(null)}
                  onDrop={(e) => {
                    e.preventDefault();
                    setDrop(null);
                    try {
                      const src = JSON.parse(e.dataTransfer.getData("application/x-ct-step")) as { phase: Phase; index: number };
                      const r = e.currentTarget.getBoundingClientRect();
                      const after = e.clientY > r.top + r.height / 2;
                      if (src.phase === sec.phase) {
                        let to = i + (after ? 1 : 0);
                        if (src.index < to) to--;
                        move(sec.phase, src.index, to);
                      }
                    } catch {
                      /* not a step */
                    }
                  }}
                  title={`${s.method} ${s.url}`}
                >
                  <span className="n">{i + 1}</span>
                  <Method m={s.method} />
                  <span className="name">{s.name || s.url}</span>
                  {st ? <StatusIcon status={st} size={13} /> : null}
                </div>
              );
            })}
          </div>
        ))}
      </div>
      {current && step ? (
        <StepEditor
          key={`${nodeId ?? "suite"}:${current.phase}:${index}`}
          step={step}
          ctx={{ nodeId, folderId, phase: current.phase, index, inherited }}
          onChange={(s, c) => update(current.phase, (l) => l.map((x, j) => (j === index ? s : x)), c)}
          onDelete={() => remove(current.phase, index)}
          onDuplicate={() => duplicate(current.phase, index)}
        />
      ) : (
        <div className="scroll" style={{ minWidth: 0 }}>{other ?? <Empty>Select a step on the left.</Empty>}</div>
      )}
    </div>
  );
}
