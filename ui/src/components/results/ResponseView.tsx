import { useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { Braces, ChevronUp, CircleX, Copy, Download, FileText, ListTree, Play, Search, X } from "lucide-react";
import type { AssertionResult, Exchange } from "../../types";
import { fmtMs, fmtSize } from "../../runs";
import type { JsonLine } from "../../results/model";
import { checkPath, jsonLines, lineText } from "../../results/model";
import { download } from "../../files";
import { useStore } from "../../store";
import { JsonView, tryParse } from "../JsonView";
import type { JsonActions } from "../JsonView";
import { Button, HttpCode, Method, copyText } from "../ui";

export interface Lane {
  name: string;
  ex: Exchange;
}

type Mode = "pretty" | "tree" | "raw";
let lastMode: Mode = "pretty";

const LINE = 20;

/** Where a failed check or a difference points in the response, with what to say there. */
interface Note {
  text: ReactNode;
  kind: "fail" | "diff";
}

function diffPath(d: string): string | null {
  const m = / at \[(.*?)\]( \(|:)/.exec(d) ?? /^Field missing in \w+: (.+)$/.exec(d);
  if (!m) return null;
  return m[1] ? `$.${m[1]}` : "$";
}

/**
 * The response of a step as it came back: pretty JSON with the lines that failed a check marked and the expected
 * value next to them, a tree, or the raw text. Compare runs show both environments side by side.
 */
export function ResponseView({ lanes, failed, diffs, actions, fileBase, onRunAgain }: {
  lanes: Lane[];
  failed: { n: number; a: AssertionResult }[];
  diffs?: string[];
  actions?: JsonActions;
  fileBase: string;
  onRunAgain?: () => void;
}) {
  const [mode, setModeState] = useState<Mode>(lastMode);
  const setMode = (m: Mode) => {
    lastMode = m;
    setModeState(m);
  };
  const [find, setFind] = useState("");
  const [hit, setHit] = useState(0);
  const findRef = useRef<HTMLInputElement>(null);

  const notes = useMemo(() => {
    const m = new Map<string, Note[]>();
    const add = (p: string, n: Note) => m.set(p, [...(m.get(p) ?? []), n]);
    for (const { n, a } of failed) {
      const p = checkPath(a.expr);
      if (!p || /^status\b/.test(a.expr)) continue;
      add(p, { kind: "fail", text: <CheckNote n={n} a={a} /> });
    }
    for (const d of diffs ?? []) {
      const p = diffPath(d);
      if (p) add(p, { kind: "diff", text: d.replace(/^.*? at \[.*?\]:?\s*/, "") || d });
    }
    return m;
  }, [failed, diffs]);

  const placed = useMemo(() => {
    const set = new Set<number>();
    const parsed = lanes.map((l) => tryParse(l.ex.responseBody));
    failed.forEach(({ a }, i) => {
      const p = checkPath(a.expr);
      if (p && !/^status\b/.test(a.expr) && parsed.some((v) => v !== undefined && hasPath(v, p))) set.add(i);
    });
    return set;
  }, [lanes, failed]);
  const unplaced = failed.filter((_, i) => !placed.has(i) || mode === "raw");

  const anyBody = lanes.some((l) => l.ex.responseBody !== undefined && l.ex.responseBody !== null);
  const isJson = lanes.some((l) => tryParse(l.ex.responseBody) !== undefined);

  return (
    <div className="resp">
      <div className="resp-tools">
        {isJson ? (
          <div className="segmented sm">
            <button className={mode === "pretty" ? "on" : ""} onClick={() => setMode("pretty")} title="Formatted JSON with failed checks marked"><Braces size={12} />Pretty</button>
            <button className={mode === "tree" ? "on" : ""} onClick={() => setMode("tree")} title="Folding tree"><ListTree size={12} />Tree</button>
            <button className={mode === "raw" ? "on" : ""} onClick={() => setMode("raw")} title="Exactly as received"><FileText size={12} />Raw</button>
          </div>
        ) : null}
        {anyBody && mode !== "tree" ? (
          <div className="find">
            <Search size={13} />
            <input ref={findRef} placeholder="Find in response" value={find} spellCheck={false}
              onChange={(e) => { setFind(e.target.value); setHit(0); }}
              onKeyDown={(e) => {
                if (e.key === "Enter") { e.preventDefault(); setHit((h) => h + (e.shiftKey ? -1 : 1)); }
                if (e.key === "Escape") { setFind(""); (e.target as HTMLInputElement).blur(); }
              }} />
            {find ? <button className="x" onClick={() => setFind("")} title="Clear"><X size={12} /></button> : null}
          </div>
        ) : null}
      </div>
      {unplaced.length ? (
        <div className="resp-fails">
          {unplaced.map(({ n, a }) => (
            <div key={n} className="resp-fail"><CircleX size={13} /><CheckNote n={n} a={a} full /></div>
          ))}
        </div>
      ) : null}
      <div className={lanes.length > 1 ? "resp-lanes" : "resp-one"}>
        {lanes.map((l) => (
          <LaneView key={l.name} lane={l} many={lanes.length > 1} mode={mode} notes={notes} find={find} hit={hit}
            actions={l.name === "Baseline" ? undefined : actions} fileBase={fileBase} onRunAgain={onRunAgain} />
        ))}
      </div>
    </div>
  );
}

function hasPath(v: unknown, path: string): boolean {
  const parts = path.replace(/^\$\.?/, "").split(/\.|(?=\[)/).filter(Boolean);
  let cur: unknown = v;
  for (const p of parts) {
    if (cur === null || typeof cur !== "object") return false;
    const idx = /^\[(\d+)]$/.exec(p);
    if (idx) {
      if (!Array.isArray(cur)) return false;
      cur = cur[Number(idx[1])];
    } else {
      if (!(p in (cur as Record<string, unknown>))) return false;
      cur = (cur as Record<string, unknown>)[p];
    }
    if (cur === undefined) return false;
  }
  return true;
}

function CheckNote({ n, a, full }: { n: number; a: AssertionResult; full?: boolean }) {
  const m = a.message ?? "";
  const i = m.lastIndexOf(", got ");
  if (m.startsWith("expected ") && i > 0) {
    const expected = m.slice(9, i);
    return (
      <span className="note-text">
        {full ? <><span className="cn">Check {n}</span><span className="mono expr">{a.expr}</span> </> : null}
        expected <Diffed text={expected} other={m.slice(i + 6)} />{full ? <>, got <span className="mono">{m.slice(i + 6)}</span></> : null}
      </span>
    );
  }
  return (
    <span className="note-text">
      {full ? <><span className="cn">Check {n}</span><span className="mono expr">{a.expr}</span> </> : null}
      {m}
    </span>
  );
}

/** A value with the part that differs from the other value marked. */
export function Diffed({ text, other }: { text: string; other: string }) {
  let p = 0;
  while (p < text.length && p < other.length && text[p] === other[p]) p++;
  let s = 0;
  while (s < text.length - p && s < other.length - p && text[text.length - 1 - s] === other[other.length - 1 - s]) s++;
  const head = text.slice(0, p);
  const mid = text.slice(p, text.length - s);
  const tail = text.slice(text.length - s);
  return <span className="mono diffed">{head}{mid ? <b>{mid}</b> : null}{tail}</span>;
}

function LaneView({ lane, many, mode, notes, find, hit, actions, fileBase, onRunAgain }: {
  lane: Lane; many: boolean; mode: Mode; notes: Map<string, Note[]>; find: string; hit: number; actions?: JsonActions; fileBase: string; onRunAgain?: () => void;
}) {
  const ex = lane.ex;
  const body = ex.responseBody;
  const parsed = useMemo(() => tryParse(body), [body]);
  const copy = async () => {
    await copyText(body ?? "");
    useStore.getState().toast("ok", "Response copied");
  };
  const save = () => {
    const ext = parsed !== undefined ? "json" : /xml/.test(ex.contentType ?? "") ? "xml" : /html/.test(ex.contentType ?? "") ? "html" : "txt";
    download(new Blob([body ?? ""], { type: ex.contentType ?? "text/plain" }), `${fileBase}${many ? "-" + lane.name.toLowerCase() : ""}.${ext}`);
  };
  return (
    <div className="lane">
      <div className="lane-head">
        {many ? <span className="lane-name">{lane.name}</span> : null}
        <HttpCode code={ex.status} />
        {ex.timeMs !== undefined ? <span className="faint">{fmtMs(ex.timeMs)}</span> : null}
        {ex.responseSize !== undefined ? <span className="faint">{fmtSize(ex.responseSize)}</span> : null}
        {ex.contentType ? <span className="faint ellipsis" style={{ maxWidth: 160 }}>{ex.contentType.replace(/;.*$/, "")}</span> : null}
        {ex.truncated ? <span className="badge warn" title="Bodies are cut at the run's size limit">cut at limit</span> : null}
        <span className="lane-url ellipsis" title={`${ex.method ?? ""} ${ex.url ?? ""}`}><Method m={ex.method} /> {ex.url}</span>
        {body ? (
          <span className="row" style={{ gap: 2, flex: "none" }}>
            <Button size="sm" variant="ghost" icon={<Copy size={13} />} onClick={copy} title="Copy the body">Copy</Button>
            <Button size="sm" variant="ghost" icon={<Download size={13} />} onClick={save} title="Save the body as a file" />
          </span>
        ) : null}
      </div>
      <LaneBody ex={ex} parsed={parsed} mode={mode} notes={notes} find={find} hit={hit} actions={actions} onRunAgain={onRunAgain} />
    </div>
  );
}

function LaneBody({ ex, parsed, mode, notes, find, hit, actions, onRunAgain }: {
  ex: Exchange; parsed: unknown; mode: Mode; notes: Map<string, Note[]>; find: string; hit: number; actions?: JsonActions; onRunAgain?: () => void;
}) {
  const body = ex.responseBody;
  if (ex.error && !body) return <div className="resp-msg"><div className="note warn">{ex.error}</div></div>;
  if (body === undefined || body === null) {
    return (
      <div className="resp-msg">
        <div className="muted">The response was not kept: this run keeps responses only for test cases that did not pass.</div>
        {onRunAgain ? <Button variant="run" size="sm" icon={<Play size={13} />} onClick={onRunAgain}>Run again and keep the response</Button> : null}
      </div>
    );
  }
  if (!body) return <div className="resp-msg muted">Empty body.</div>;
  if (mode === "tree" && parsed !== undefined) {
    const hl = new Set(notes.keys());
    return <div className="resp-scroll tree"><JsonView value={parsed} actions={actions} highlight={hl} /></div>;
  }
  if (mode === "pretty" && parsed !== undefined) return <PrettyLines value={parsed} notes={notes} find={find} hit={hit} />;
  return <RawText text={body} find={find} hit={hit} />;
}

function PrettyLines({ value, notes, find, hit }: { value: unknown; notes: Map<string, Note[]>; find: string; hit: number }) {
  const lines = useMemo(() => jsonLines(value), [value]);
  const texts = useMemo(() => lines.map(lineText), [lines]);
  return <Lines count={lines.length} texts={texts} find={find} hit={hit} firstNote={firstNoted(lines, notes)}
    render={(i) => <JsonLineView l={lines[i]} notes={notes.get(lines[i].path)} />} />;
}

function firstNoted(lines: JsonLine[], notes: Map<string, Note[]>): number {
  if (!notes.size) return -1;
  return lines.findIndex((l) => notes.has(l.path));
}

function RawText({ text, find, hit }: { text: string; find: string; hit: number }) {
  const texts = useMemo(() => text.split(/\r?\n/), [text]);
  if (texts.length < 3 && text.length > 2000) {
    return <div className="resp-scroll"><pre className="raw-wrap">{find ? marked(text, find) : text}</pre></div>;
  }
  return <Lines count={texts.length} texts={texts} find={find} hit={hit} firstNote={-1} render={(i) => <span>{texts[i]}</span>} />;
}

/** A long list of numbered lines, drawn only where visible, with find and a jump to the first failed check. */
function Lines({ count, texts, find, hit, firstNote, render }: {
  count: number; texts: string[]; find: string; hit: number; firstNote: number; render: (i: number) => ReactNode;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const v = useVirtualizer({ count, getScrollElement: () => ref.current, estimateSize: () => LINE, overscan: 30 });
  const matches = useMemo(() => {
    if (!find) return [] as number[];
    const q = find.toLowerCase();
    const out: number[] = [];
    texts.forEach((t, i) => t.toLowerCase().includes(q) && out.push(i));
    return out;
  }, [texts, find]);
  const cur = matches.length ? matches[((hit % matches.length) + matches.length) % matches.length] : -1;
  useEffect(() => {
    if (cur >= 0) v.scrollToIndex(cur, { align: "center" });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cur]);
  useEffect(() => {
    if (firstNote > 4 && !find) v.scrollToIndex(Math.max(0, firstNote - 4), { align: "start" });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [firstNote]);
  const digits = String(count).length;
  const matchSet = useMemo(() => new Set(matches), [matches]);
  return (
    <div className="resp-scroll lines" ref={ref} style={{ ["--gutter" as string]: `${digits + 2.5}ch` }}>
      {find ? <div className="find-count">{matches.length ? `${matches.indexOf(cur) + 1} of ${matches.length}` : "No match"}</div> : null}
      <div style={{ height: v.getTotalSize(), position: "relative", minWidth: "100%", width: "max-content" }}>
        {v.getVirtualItems().map((vi) => (
          <div key={vi.index} className={`ln${vi.index === cur ? " cur" : matchSet.has(vi.index) ? " hit" : ""}`}
            style={{ transform: `translateY(${vi.start}px)` }}>
            <span className="no">{vi.index + 1}</span>
            <span className="tx">{find && matchSet.has(vi.index) ? marked(texts[vi.index], find) : render(vi.index)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function marked(text: string, q: string): ReactNode {
  const out: ReactNode[] = [];
  const low = text.toLowerCase();
  const ql = q.toLowerCase();
  let i = 0;
  let k = 0;
  for (;;) {
    const j = low.indexOf(ql, i);
    if (j < 0) break;
    out.push(text.slice(i, j), <mark key={k++}>{text.slice(j, j + q.length)}</mark>);
    i = j + q.length;
  }
  out.push(text.slice(i));
  return out;
}

function JsonLineView({ l, notes }: { l: JsonLine; notes?: Note[] }) {
  return (
    <span className={notes?.length ? (notes.some((n) => n.kind === "fail") ? "jl bad" : "jl diff") : "jl"}>
      {"  ".repeat(l.depth)}
      {l.key !== undefined ? <><span className="jk">{JSON.stringify(l.key)}</span><span className="jp">: </span></> : null}
      {l.value !== undefined ? <span className={`j-${l.vtype}`}>{l.value}</span> : null}
      {l.open ? <span className="jp">{l.open}</span> : null}
      {l.close ? <span className="jp">{l.close}</span> : null}
      {l.comma ? <span className="jp">,</span> : null}
      {l.open && l.count && l.open.length === 1 ? <span className="jc">{l.count} {l.open === "[" ? (l.count === 1 ? "item" : "items") : (l.count === 1 ? "key" : "keys")}</span> : null}
      {notes?.map((n, i) => (
        <span key={i} className={`ann ${n.kind}`}>{n.kind === "fail" ? <CircleX size={12} /> : <ChevronUp size={12} style={{ transform: "rotate(90deg)" }} />}{n.text}</span>
      ))}
    </span>
  );
}

