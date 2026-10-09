import type { RunView } from "./runs";
import { compareRuns, failureSignature, hookKey } from "./runs";
import type { Filter } from "./store";
import { find } from "./suiteOps";
import type { CaseResult, HookResult, StatusValue, Suite } from "./types";

export type CaseState = StatusValue | "running" | "queued";

export type RunRow =
  | { kind: "group"; id: string; label: string; count: number; worst?: string }
  | { kind: "hook"; id: string; hook: HookResult; label: string }
  | { kind: "case"; id: string; caseId: string; result?: CaseResult; state: CaseState; key: string; name: string; change?: string };

export interface RowInput {
  run: RunView;
  suite: Suite | null;
  filter: Filter;
  search: string;
  grouping: "tree" | "groups";
  older?: RunView;
}

const BAD: CaseState[] = ["fail", "error", "blocked"];

export function caseState(run: RunView, id: string): CaseState {
  const r = run.cases[id];
  if (r) return r.status;
  if (run.running[id]) return "running";
  return "queued";
}

export function matchesFilter(state: CaseState, filter: Filter): boolean {
  if (filter === "all") return true;
  if (filter === "notpass") return BAD.includes(state);
  return state === filter;
}

function textOf(run: RunView, suite: Suite | null, id: string): { key: string; name: string; path: string[]; tags: string[] } {
  const r = run.cases[id];
  if (r) return { key: r.key ?? "", name: r.name ?? "", path: r.path ?? [], tags: r.tags ?? [] };
  const loc = suite ? find(suite, id) : null;
  if (loc && loc.node.type === "case") return { key: loc.node.key, name: loc.node.name, path: loc.path.map((f) => f.name), tags: loc.node.tags ?? [] };
  return { key: id, name: "", path: [], tags: [] };
}

function matchesSearch(q: string, t: { key: string; name: string; path: string[]; tags: string[] }, r?: CaseResult): boolean {
  if (!q) return true;
  const tags: string[] = [];
  const text = q.toLowerCase().replace(/(?:^|\s)(?:#|tag:)(\S+)/g, (_, tag: string) => {
    tags.push(tag);
    return " ";
  }).trim();
  if (tags.length && !tags.every((tg) => t.tags.some((x) => x.toLowerCase().includes(tg)))) return false;
  if (!text) return true;
  const hay = `${t.key} ${t.name} ${t.path.join("/")} ${r?.reason ?? ""} ${r?.triage?.label ?? ""} ${r?.triage?.note ?? ""}`.toLowerCase();
  return text.split(/\s+/).every((w) => hay.includes(w));
}

let cacheKey: unknown[] = [];
let cacheRows: RunRow[] = [];

/** The rows of the run list for the current filter, search and grouping (cached on its inputs). */
export function runRows(input: RowInput): RunRow[] {
  const key = [input.run, input.suite, input.filter, input.search, input.grouping, input.older];
  if (key.length === cacheKey.length && key.every((k, i) => k === cacheKey[i])) return cacheRows;
  cacheKey = key;
  cacheRows = build(input);
  return cacheRows;
}

function build({ run, suite, filter, search, grouping, older }: RowInput): RunRow[] {
  const ids = run.header.caseIds?.length ? run.header.caseIds : Object.keys(run.cases);
  const changes = older ? compareRuns(older, run) : {};
  const rows: RunRow[] = [];

  const hooks = run.hooks.filter((h) => (filter === "all" ? true : filter === "notpass" ? h.status !== "pass" : h.status === filter))
    .filter((h) => !search || `${h.path?.join("/") ?? "suite"} ${h.hook} ${h.reason ?? ""}`.toLowerCase().includes(search.toLowerCase()));
  if (hooks.length) {
    rows.push({ kind: "group", id: "g:hooks", label: "Setup and teardown", count: hooks.length });
    for (const h of hooks) {
      rows.push({ kind: "hook", id: hookKey(h), hook: h, label: `${h.path?.length ? h.path.join(" / ") : "Suite"} · ${h.hook}` });
    }
  }

  const caseRows: (RunRow & { kind: "case" })[] = [];
  const paths: Record<string, string> = {};
  for (const id of ids) {
    const state = caseState(run, id);
    if (!matchesFilter(state, filter)) continue;
    const t = textOf(run, suite, id);
    const r = run.cases[id];
    if (!matchesSearch(search, t, r)) continue;
    paths[id] = t.path.join(" / ");
    caseRows.push({ kind: "case", id: `c:${id}`, caseId: id, result: r, state, key: t.key, name: t.name, change: changes[id] });
  }

  if (grouping === "groups") {
    const groups = new Map<string, (RunRow & { kind: "case" })[]>();
    for (const c of caseRows) {
      const sig = c.result ? (c.result.status === "pass" ? "Passed" : failureSignature(c.result)) : c.state === "running" ? "Running" : "Not run yet";
      const list = groups.get(sig) ?? [];
      list.push(c);
      groups.set(sig, list);
    }
    const sorted = [...groups.entries()].sort((a, b) => {
      const pa = a[0] === "Passed" || a[0] === "Not run yet" || a[0] === "Running" ? 1 : 0;
      const pb = b[0] === "Passed" || b[0] === "Not run yet" || b[0] === "Running" ? 1 : 0;
      return pa - pb || b[1].length - a[1].length;
    });
    sorted.forEach(([sig, list], i) => {
      rows.push({ kind: "group", id: `g:sig:${i}`, label: sig, count: list.length, worst: list[0].state });
      rows.push(...list);
    });
    return rows;
  }

  const perPath: Record<string, number> = {};
  for (const c of caseRows) perPath[paths[c.caseId]] = (perPath[paths[c.caseId]] ?? 0) + 1;
  let last: string | null = null;
  for (const c of caseRows) {
    const p = paths[c.caseId];
    if (p !== last) {
      rows.push({ kind: "group", id: `g:path:${p}:${rows.length}`, label: p || "Top level", count: perPath[p] });
      last = p;
    }
    rows.push(c);
  }
  return rows;
}

export function stateCounts(run: RunView): Record<string, number> {
  const out: Record<string, number> = { all: 0, notpass: 0, pass: 0, fail: 0, error: 0, blocked: 0, skipped: 0, running: 0, queued: 0 };
  const ids = run.header.caseIds?.length ? run.header.caseIds : Object.keys(run.cases);
  for (const id of ids) {
    const s = caseState(run, id);
    out.all++;
    out[s] = (out[s] ?? 0) + 1;
    if (BAD.includes(s)) out.notpass++;
  }
  return out;
}
