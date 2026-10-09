import { useMemo, useState } from "react";
import type { ReactNode } from "react";

/** Collapsible JSON tree. Each value can be turned into an assertion or an extracted variable with one click. */
export interface JsonActions {
  onAssert?: (line: string) => void;
  onExtract?: (name: string, path: string) => void;
}

const PAGE = 100;

export function JsonView({ value, actions, highlight }: { value: unknown; actions?: JsonActions; highlight?: Set<string> }) {
  const size = useMemo(() => countNodes(value, 3000), [value]);
  const [collapsed, setCollapsed] = useState<Set<string>>(() => initialCollapsed(value, size));
  const [shown, setShown] = useState<Record<string, number>>({});
  const toggle = (p: string) =>
    setCollapsed((c) => {
      const n = new Set(c);
      if (n.has(p)) n.delete(p);
      else n.add(p);
      return n;
    });

  const lines: ReactNode[] = [];
  const emit = (node: ReactNode) => lines.push(node);

  const walk = (v: unknown, path: string, key: string | number | null, depth: number, last: boolean) => {
    const indent = { paddingLeft: depth * 14 };
    const comma = last ? "" : ",";
    const keyEl = key === null ? null : typeof key === "number" ? null : (
      <>
        <span className="jv-key">"{key}"</span>
        <span className="jv-punc">: </span>
      </>
    );
    const hit = highlight?.has(path) ? " jv-hit" : "";
    if (v !== null && typeof v === "object") {
      const isArr = Array.isArray(v);
      const entries: [string | number, unknown][] = isArr ? (v as unknown[]).map((x, i) => [i, x]) : Object.entries(v as Record<string, unknown>);
      const open = isArr ? "[" : "{";
      const close = isArr ? "]" : "}";
      const isCollapsed = collapsed.has(path);
      if (!entries.length) {
        emit(
          <div className={`jv-line${hit}`} style={indent} key={path}>
            <span className="jv-toggle" />
            {keyEl}
            <span className="jv-punc">{open}{close}{comma}</span>
            <Acts path={path} keyName={key} value={v} actions={actions} />
          </div>,
        );
        return;
      }
      emit(
        <div className={`jv-line${hit}`} style={indent} key={path}>
          <button className="jv-toggle" onClick={() => toggle(path)} aria-label={isCollapsed ? "Expand" : "Collapse"}>{isCollapsed ? "▸" : "▾"}</button>
          {keyEl}
          <span className="jv-punc">{open}</span>
          {isCollapsed ? (
            <>
              <button className="jv-more" onClick={() => toggle(path)}>{isArr ? `${entries.length} items` : `${entries.length} keys`}</button>
              <span className="jv-punc">{close}{comma}</span>
            </>
          ) : null}
          <Acts path={path} keyName={key} value={v} actions={actions} />
        </div>,
      );
      if (isCollapsed) return;
      const limit = shown[path] ?? PAGE;
      entries.slice(0, limit).forEach(([k, x], i) => {
        const childPath = typeof k === "number" ? `${path}[${k}]` : `${path}.${k}`;
        walk(x, childPath, k, depth + 1, i === Math.min(entries.length, limit) - 1 && entries.length <= limit);
      });
      if (entries.length > limit) {
        emit(
          <div className="jv-line" style={{ paddingLeft: (depth + 1) * 14 }} key={path + "#more"}>
            <span className="jv-toggle" />
            <button className="jv-more" onClick={() => setShown((s) => ({ ...s, [path]: limit + PAGE * 5 }))}>
              … {entries.length - limit} more
            </button>
          </div>,
        );
      }
      emit(
        <div className="jv-line" style={indent} key={path + "#end"}>
          <span className="jv-toggle" />
          <span className="jv-punc">{close}{comma}</span>
        </div>,
      );
      return;
    }
    emit(
      <div className={`jv-line${hit}`} style={indent} key={path}>
        <span className="jv-toggle" />
        {keyEl}
        <Scalar v={v} />
        <span className="jv-punc">{comma}</span>
        <Acts path={path} keyName={key} value={v} actions={actions} />
      </div>,
    );
  };

  walk(value, "$", null, 0, true);
  return <div className="jv">{lines}</div>;
}

function Scalar({ v }: { v: unknown }) {
  if (v === null) return <span className="jv-null">null</span>;
  if (typeof v === "string") return <span className="jv-str">"{v}"</span>;
  if (typeof v === "number") return <span className="jv-num">{String(v)}</span>;
  if (typeof v === "boolean") return <span className="jv-bool">{String(v)}</span>;
  return <span>{String(v)}</span>;
}

function Acts({ path, keyName, value, actions }: { path: string; keyName: string | number | null; value: unknown; actions?: JsonActions }) {
  if (!actions || (!actions.onAssert && !actions.onExtract)) return null;
  const isObj = value !== null && typeof value === "object";
  return (
    <span className="acts">
      {actions.onAssert && !isObj ? (
        <button title={`Add assertion: ${assertionFor(path, value)}`} onClick={() => actions.onAssert!(assertionFor(path, value))}>assert</button>
      ) : null}
      {actions.onAssert && isObj && Array.isArray(value) ? (
        <button title={`Add assertion: ${path}.length == ${value.length}`} onClick={() => actions.onAssert!(`${path}.length == ${value.length}`)}>length</button>
      ) : null}
      {actions.onAssert && path !== "$" ? (
        <button title={`Add assertion: ${path} exists`} onClick={() => actions.onAssert!(`${path} exists`)}>exists</button>
      ) : null}
      {actions.onExtract && !isObj ? (
        <button title={`Save as variable: ${path}`} onClick={() => actions.onExtract!(varName(keyName, path), path)}>extract</button>
      ) : null}
    </span>
  );
}

export function assertionFor(path: string, v: unknown): string {
  if (v === null) return `${path} is null`;
  if (typeof v === "string") return `${path} == ${JSON.stringify(v)}`;
  return `${path} == ${String(v)}`;
}

function varName(key: string | number | null, path: string): string {
  if (typeof key === "string" && /^[A-Za-z_][\w]*$/.test(key)) return key;
  const parts = path.replace(/\[\d+]/g, "").split(".").filter((p) => p && p !== "$");
  const last = parts[parts.length - 1] ?? "value";
  return last.replace(/[^\w]/g, "_") || "value";
}

function countNodes(v: unknown, cap: number): number {
  let n = 0;
  const stack: unknown[] = [v];
  while (stack.length && n < cap) {
    const x = stack.pop();
    n++;
    if (x !== null && typeof x === "object") {
      for (const c of Array.isArray(x) ? x : Object.values(x as Record<string, unknown>)) stack.push(c);
    }
  }
  return n;
}

/** Large documents open with their deeper levels folded so the first screen stays readable. */
function initialCollapsed(v: unknown, size: number): Set<string> {
  const out = new Set<string>();
  if (size < 400) return out;
  const maxDepth = size < 1500 ? 3 : 2;
  const visit = (x: unknown, path: string, depth: number) => {
    if (x === null || typeof x !== "object") return;
    if (depth >= maxDepth) {
      out.add(path);
      return;
    }
    if (Array.isArray(x)) x.slice(0, PAGE).forEach((c, i) => visit(c, `${path}[${i}]`, depth + 1));
    else Object.entries(x as Record<string, unknown>).forEach(([k, c]) => visit(c, `${path}.${k}`, depth + 1));
  };
  visit(v, "$", 0);
  return out;
}

/** Parses a response body for display; returns undefined when it is not JSON. */
export function tryParse(text?: string): unknown {
  if (text === undefined || text === null) return undefined;
  const t = text.trim();
  if (!t || (t[0] !== "{" && t[0] !== "[")) return undefined;
  try {
    return JSON.parse(t);
  } catch {
    return undefined;
  }
}

export function pretty(text?: string): string {
  if (!text) return "";
  const v = tryParse(text);
  return v === undefined ? text : JSON.stringify(v, null, 2);
}
