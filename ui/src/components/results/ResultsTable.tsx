import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { CSSProperties, MouseEvent as ReactMouseEvent, ReactNode } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { ArrowDown, Check, ChevronDown, ChevronRight, Folder, FolderOpen, ListEnd, ListStart, Minus } from "lucide-react";
import { fmtMs } from "../../runs";
import type { ResultsData } from "../../results/compute";
import type { CaseInfo, Failure, Row, SortBy, Stats } from "../../results/model";
import { labelClass, labelName, shortPath, shortReason, stepFailure, stepTitle, trimCommon } from "../../results/model";
import type { StepResult } from "../../types";
import { useStore } from "../../store";
import type { ColumnId } from "../../store";
import { Button, HttpCode, StatusIcon } from "../ui";

type Col = { id: "cb" | "st" | "key" | "name" | ColumnId; w: string; min: number };

const ALL_COLS: Col[] = [
  { id: "cb", w: "30px", min: 30 },
  { id: "st", w: "22px", min: 22 },
  { id: "key", w: "minmax(140px, 190px)", min: 140 },
  { id: "name", w: "minmax(180px, 1.1fr)", min: 180 },
  { id: "failedAt", w: "minmax(150px, 220px)", min: 150 },
  { id: "what", w: "minmax(200px, 1.8fr)", min: 200 },
  { id: "http", w: "44px", min: 44 },
  { id: "time", w: "64px", min: 64 },
  { id: "change", w: "100px", min: 100 },
  { id: "label", w: "68px", min: 68 },
];
/** Columns dropped first when the list is narrow. */
const DROP_ORDER: ColumnId[] = ["failedAt", "http", "label", "change", "time"];

function useWidth(ref: React.RefObject<HTMLElement>): number {
  const [w, setW] = useState(1200);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setW(el.clientWidth));
    ro.observe(el);
    setW(el.clientWidth);
    return () => ro.disconnect();
  }, [ref]);
  return w;
}

let savedScroll = 0;

export function ResultsTable({ data }: { data: ResultsData }) {
  const columns = useStore((s) => s.columns);
  const runSel = useStore((s) => s.runSel);
  const checked = useStore((s) => s.checked);
  const sortBy = useStore((s) => s.sortBy);
  const filter = useStore((s) => s.filter);
  const search = useStore((s) => s.search);
  const delta = useStore((s) => s.delta);
  const ref = useRef<HTMLDivElement>(null);
  const width = useWidth(ref);
  const anchor = useRef<string | null>(null);
  const rows = data.rows;

  const cols = useMemo(() => {
    let list = ALL_COLS.filter((c) => !(c.id in columns) || columns[c.id as ColumnId]);
    const need = () => list.reduce((n, c) => n + c.min, 0) + list.length * 10 + 32;
    for (const id of DROP_ORDER) {
      if (need() <= width) break;
      list = list.filter((c) => c.id !== id);
    }
    return list;
  }, [columns, width]);
  const template = cols.map((c) => c.w).join(" ");
  const at = (id: Col["id"]) => {
    const i = cols.findIndex((c) => c.id === id);
    return i < 0 ? undefined : i + 1;
  };

  const v = useVirtualizer({
    count: rows.length,
    getScrollElement: () => ref.current,
    estimateSize: (i) => (rows[i]?.kind === "group" && rows[i].level === 0 ? 36 : 31),
    overscan: 20,
    initialOffset: savedScroll,
  });

  const focusId = runSel.caseId ?? runSel.hook ?? null;
  useEffect(() => {
    if (!focusId) return;
    const i = rows.findIndex((r) => r.id === focusId);
    if (i >= 0) v.scrollToIndex(i, { align: "auto" });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focusId, rows.length]);

  const st = useStore.getState();
  const allChecked = data.matched.length > 0 && data.matched.every((id) => checked[id]);
  const someChecked = data.matched.some((id) => checked[id]);

  const toggleCase = (id: string, e: ReactMouseEvent) => {
    e.stopPropagation();
    const on = !checked[id];
    if (e.shiftKey && anchor.current && data.order.includes(anchor.current)) {
      const a = data.order.indexOf(anchor.current);
      const b = data.order.indexOf(id);
      st.setChecked(data.order.slice(Math.min(a, b), Math.max(a, b) + 1), on);
    } else st.setChecked([id], on);
    anchor.current = id;
  };

  const head = (
    <div className="rgrid rhead" style={{ gridTemplateColumns: template }}>
      <span className="cbx" onClick={() => st.setChecked(data.matched, !allChecked)} title={allChecked ? "Clear the selection" : "Select every test case that matches"}>
        <Box on={allChecked} part={!allChecked && someChecked} />
      </span>
      <span />
      <SortHead id="key" label="Key" sortBy={sortBy} />
      <SortHead id="name" label="Test case" sortBy={sortBy} />
      {at("failedAt") ? <span>Failed at</span> : null}
      {at("what") ? <span>What failed</span> : null}
      {at("http") ? <span className="r">HTTP</span> : null}
      {at("time") ? <SortHead id="time" label="Time" sortBy={sortBy} right /> : null}
      {at("change") ? <span>vs previous</span> : null}
      {at("label") ? <span>Label</span> : null}
    </div>
  );

  return (
    <div className="rtable" ref={ref} onScroll={(e) => (savedScroll = (e.target as HTMLElement).scrollTop)} tabIndex={-1}>
      {head}
      {rows.length === 0 ? <EmptyList filter={filter} search={search} delta={!!delta} run={data} /> : null}
      <div style={{ height: v.getTotalSize(), position: "relative" }}>
        {v.getVirtualItems().map((vi) => {
          const r = rows[vi.index];
          const style: CSSProperties = { position: "absolute", top: 0, left: 0, right: 0, transform: `translateY(${vi.start}px)`, gridTemplateColumns: template };
          if (r.kind === "group") return <GroupRow key={r.id} r={r} style={style} at={at} checked={checked} />;
          if (r.kind === "hook") return <HookRow key={r.id} r={r} style={style} at={at} focused={focusId === r.id} />;
          return <CaseRow key={r.id} r={r} style={style} at={at} focused={focusId === r.id} checked={!!checked[r.id]} onCheck={toggleCase} />;
        })}
      </div>
    </div>
  );
}

function SortHead({ id, label, sortBy, right }: { id: SortBy; label: string; sortBy: SortBy; right?: boolean }) {
  const on = sortBy === id;
  return (
    <button className={`sorth${on ? " on" : ""}${right ? " r" : ""}`} title={on ? "Back to suite order" : `Sort by ${label.toLowerCase()}`}
      onClick={() => useStore.getState().setSort(on ? "suite" : id)}>
      {label}{on ? <ArrowDown size={11} /> : null}
    </button>
  );
}

export function Box({ on, part }: { on: boolean; part?: boolean }) {
  return <span className={`box${on ? " on" : part ? " part" : ""}`}>{on ? <Check size={10} strokeWidth={3} /> : part ? <Minus size={10} strokeWidth={3} /> : null}</span>;
}

function EmptyList({ filter, search, delta, run }: { filter: string; search: string; delta: boolean; run: ResultsData }) {
  const st = useStore.getState();
  const done = run.run?.done;
  return (
    <div className="rempty">
      {filter === "notpass" && !search && !delta && done ? (
        <><StatusIcon status="pass" size={18} /><span>Every test case that ran passed.</span></>
      ) : <span>Nothing matches{search ? ` “${search}”` : ""}.</span>}
      {filter !== "all" || search || delta ? (
        <Button size="sm" onClick={() => { st.setFilter("all"); st.setSearch(""); st.setDelta(undefined); }}>Show every test case</Button>
      ) : null}
    </div>
  );
}

// ---------------------------------------------------------------- rows

type At = (id: Col["id"]) => number | undefined;

function Mini({ s }: { s: Stats }) {
  const total = Math.max(1, s.run);
  const seg = (n: number, cls: string) => (n ? <i className={cls} style={{ width: `${(n / total) * 100}%` }} /> : null);
  return (
    <span className="mini" title={`${s.counts.pass} passed of ${s.run}`}>
      {seg(s.counts.pass, "pass")}{seg(s.counts.fail, "fail")}{seg(s.counts.error, "error")}{seg(s.counts.blocked, "blocked")}{seg(s.counts.skipped, "skipped")}
      {seg(s.counts.running + s.counts.queued, "running")}
    </span>
  );
}

function Pills({ s, setupFailed }: { s: Stats; setupFailed?: boolean }) {
  const c = s.counts;
  return (
    <span className="pills">
      {c.fail ? <span className="cpill fail">{c.fail} failed</span> : null}
      {c.error ? <span className="cpill error">{c.error} error</span> : null}
      {c.blocked ? <span className="cpill blocked">{c.blocked} blocked</span> : null}
      {c.skipped ? <span className="cpill skipped">{c.skipped} skipped</span> : null}
      {setupFailed ? <span className="cpill hookbad"><ListStart size={11} />setup failed</span> : null}
      {c.disabled ? <span className="cpill off">{c.disabled} disabled</span> : null}
    </span>
  );
}

function Changes({ s }: { s: Stats }) {
  const c = s.changes;
  return (
    <span className="chg">
      {c.new ? <span className="badge new">{c.new} new</span> : null}
      {c.still ? <span className="badge still">{c.still} still</span> : null}
      {c.fixed ? <span className="badge fixed">{c.fixed} fixed</span> : null}
    </span>
  );
}

function GroupRow({ r, style, at, checked }: { r: Extract<Row, { kind: "group" }>; style: CSSProperties; at: At; checked: Record<string, true> }) {
  const st = useStore.getState();
  const all = r.ids.length > 0 && r.ids.every((id) => checked[id]);
  const some = r.ids.some((id) => checked[id]);
  const statsAt = at("failedAt") ?? at("what");
  const pillsAt = at("what");
  const nameEnd = statsAt ?? at("http") ?? at("time") ?? at("change") ?? at("label");
  const s = r.stats;
  const toggle = () => r.canOpen && st.setGroupOpen([r.id], !r.open);
  const folder = r.by === "folder";
  const label: ReactNode = r.by === "cause" && r.sample ? <span className="cause">{r.label}</span> : r.label;
  return (
    <div className={`rgrid rrow grp l${r.level}${r.open ? " open" : ""}${!r.canOpen ? " still" : ""}`} style={style} onClick={toggle}>
      <span className="cbx" onClick={(e) => { e.stopPropagation(); if (r.ids.length) st.setChecked(r.ids, !all); }}
        title={r.ids.length ? `Select the ${r.ids.length} test case${r.ids.length > 1 ? "s" : ""} of this group that match` : undefined}>
        {r.ids.length ? <Box on={all} part={!all && some} /> : null}
      </span>
      <span className="chev">{r.canOpen ? (r.open ? <ChevronDown size={14} /> : <ChevronRight size={14} />) : <ChevronRight size={14} className="faint" />}</span>
      <span className="gname" style={{ gridColumn: `${at("key")} / ${nameEnd ?? "-1"}`, paddingLeft: r.level * 16 }}>
        {folder ? (r.open ? <FolderOpen size={15} className="fico" /> : <Folder size={15} className="fico" />) : <StatusIcon status={r.state ?? r.sample?.state} size={14} />}
        <span className="ellipsis" title={typeof r.label === "string" ? r.label : undefined}>{label}</span>
        {!folder ? <span className="gcount">{r.stats.total}</span> : null}
      </span>
      {statsAt ? (
        <span className={`gstat${pillsAt === statsAt ? " compact" : ""}`}>
          <Mini s={s} />
          <span className="frac"><b>{s.counts.pass}</b> / {s.run}{pillsAt === statsAt ? "" : " passed"}</span>
          {!pillsAt || pillsAt === statsAt ? <Pills s={s} setupFailed={r.badHooks?.some((h) => h.hook === "setup")} /> : null}
        </span>
      ) : null}
      {pillsAt && pillsAt !== statsAt ? <span className="gpills"><Pills s={s} setupFailed={r.badHooks?.some((h) => h.hook === "setup")} /></span> : null}
      {at("http") ? <span /> : null}
      {at("time") ? <span className="rnum">{s.durationMs ? fmtMs(s.durationMs) : ""}</span> : null}
      {at("change") ? <span className="cell"><Changes s={s} /></span> : null}
      {at("label") ? <span className="cell faint small">{s.labelled ? `${s.labelled} labelled` : ""}</span> : null}
    </div>
  );
}

function HookRow({ r, style, at, focused }: { r: Extract<Row, { kind: "hook" }>; style: CSSProperties; at: At; focused: boolean }) {
  const st = useStore.getState();
  const h = r.hook;
  const bad = h.steps?.find((s) => s.status !== "pass" && s.status !== "skipped");
  const what = at("what");
  return (
    <div className={`rgrid rrow hook${focused ? " focus" : ""}`} style={style} onClick={() => st.selectRun({ hook: r.id })}>
      <span />
      <span>{h.hook === "setup" ? <ListStart size={15} className={`st-${h.status}`} /> : <ListEnd size={15} className={`st-${h.status}`} />}</span>
      <span className="cell hk" style={{ paddingLeft: Math.max(0, r.level - 1) * 16 }}>{h.hook === "setup" ? "Setup" : "Teardown"}{bad ? ` · ${stepTitle(bad).replace(/^Setup |^Teardown /, "step ")}` : ""}</span>
      <span className="cell" title={bad?.name}>{bad?.name ?? (h.path?.length ? h.path[h.path.length - 1] : "Suite")}</span>
      {at("failedAt") ? (
        <span className="cell">
          {r.blocked.length ? (
            <button className="link blk" onClick={(e) => { e.stopPropagation(); st.setGroupOpen([r.id], !r.open); }}>
              {r.open ? <ChevronDown size={13} /> : <ChevronRight size={13} />}{r.blocked.length} test case{r.blocked.length > 1 ? "s" : ""} blocked
            </button>
          ) : null}
        </span>
      ) : null}
      {what ? <span className="cell wf" title={h.reason}>{bad ? <HookFailure s={bad} /> : <span className="ms">{shortReason(h.reason ?? "")}</span>}</span> : null}
      {at("http") ? <span className="r"><HttpCode code={bad?.target?.status} /></span> : null}
      {at("time") ? <span className="rnum">{fmtMs(h.durationMs)}</span> : null}
      {at("change") ? <span /> : null}
      {at("label") ? <span /> : null}
    </div>
  );
}

function HookFailure({ s }: { s: StepResult }) {
  const f = stepFailure(s);
  return f ? <FailureLine f={f} /> : <span className="ms">{s.reason}</span>;
}

function CaseRow({ r, style, at, focused, checked, onCheck }: {
  r: Extract<Row, { kind: "case" }>; style: CSSProperties; at: At; focused: boolean; checked: boolean; onCheck: (id: string, e: ReactMouseEvent) => void;
}) {
  const i = r.info;
  const st = useStore.getState();
  const offSteps = i.result?.disabledSteps?.length ?? 0;
  return (
    <div className={`rgrid rrow case${focused ? " focus" : ""}${checked ? " chk" : ""} s-${i.state}`} style={style}
      onClick={() => st.selectRun({ caseId: i.id, tab: st.runSel.tab })} title={i.result?.reason ?? undefined}>
      <span className="cbx" onClick={(e) => onCheck(i.id, e)}><Box on={checked} /></span>
      <span><StatusIcon status={i.state} size={15} /></span>
      <span className="cell key">{i.key}</span>
      <span className="cell nm">
        <span className="ellipsis">{i.name}</span>
        {i.attempts > 1 ? <span className="tag-mini" title={`Ran ${i.attempts} times in this run; this is the latest`}>{i.attempts} runs</span> : null}
        {offSteps ? <span className="tag-mini off" title={`${offSteps} disabled step${offSteps > 1 ? "s" : ""} did not run`}>{offSteps} off</span> : null}
        {i.result?.cleanupFailed ? <span className="tag-mini warn" title="Cleanup did not pass; test data may be left behind">cleanup</span> : null}
      </span>
      {at("failedAt") ? <span className="cell at"><FailedAt i={i} /></span> : null}
      {at("what") ? <span className="cell wf"><What i={i} /></span> : null}
      {at("http") ? <span className="r"><HttpCode code={i.http} /></span> : null}
      {at("time") ? <span className="rnum">{i.durationMs !== undefined && i.result ? fmtMs(i.durationMs) : ""}</span> : null}
      {at("change") ? <span className="cell"><ChangeBadge c={i.change} /></span> : null}
      {at("label") ? <span className="cell">{i.label ? <span className={`lab ${labelClass(i.label)}`}>{labelName(i.label)}</span> : i.note ? <span className="faint small" title={i.note}>note</span> : null}</span> : null}
    </div>
  );
}

function ChangeBadge({ c }: { c?: string }) {
  if (c === "new") return <span className="badge new" title="Passed in the run it is compared with">New</span>;
  if (c === "still") return <span className="badge still" title="Did not pass in the run it is compared with either">Still failing</span>;
  if (c === "fixed") return <span className="badge fixed" title="Did not pass in the run it is compared with">Fixed</span>;
  return null;
}

function FailedAt({ i }: { i: CaseInfo }) {
  if (i.state === "disabled") return <span className="faint">{i.disabled?.folder ? `Folder ${i.disabled.folder} is disabled` : "Disabled"}</span>;
  const f = i.failure;
  if (!f) return null;
  if (!f.at) return <span className="faint">{f.kind === "blocked" ? "Before it started" : ""}</span>;
  return <><span className="ph">{stepTitle(f.at)}</span> {f.at.name}</>;
}

export function What({ i }: { i: CaseInfo }) {
  const f = i.failure;
  if (!f) return null;
  return <FailureLine f={f} />;
}

export function FailureLine({ f }: { f: Failure }) {
  if (f.kind === "check" && f.expr) {
    const path = shortPath(/^\S+/.exec(f.expr)?.[0] ?? f.expr);
    if (f.expected !== undefined && f.actual !== undefined) {
      const [e, a] = trimCommon(f.expected, f.actual);
      return (
        <>
          <span className="ex">{path}</span> <span className="ms">got <q>{a}</q>, expected <q>{e}</q></span>
          {f.more ? <span className="more" title={`${f.more} more failed in this step`}>+{f.more}</span> : null}
        </>
      );
    }
    return <><span className="ex">{f.expr}</span> <span className="ms">{f.message}</span>{f.more ? <span className="more">+{f.more}</span> : null}</>;
  }
  if (f.kind === "status") {
    return (
      <>
        <span className="ex">HTTP {f.actual ?? f.http}</span> <span className="ms">{f.expected ? `(expected ${f.expected})` : ""}{f.hint ? ` · ${f.hint}` : ""}</span>
        {f.more ? <span className="more">+{f.more}</span> : null}
      </>
    );
  }
  if (f.kind === "diff") return <><span className="ms">{f.message}</span>{f.more ? <span className="more">+{f.more}</span> : null}</>;
  return <span className="ms">{shortReason(f.message)}{f.hint ? ` · ${f.hint}` : ""}</span>;
}
