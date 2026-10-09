// What the results screen shows, computed from a run: one entry per test case (disabled ones included), the reason
// each did not pass, how it moved since an earlier run, and the rows of the grouped list.
import type { RunView } from "../runs";
import { caseOrder, failureSignature, firstFailure, hookKey, latestHooks } from "../runs";
import type { CaseResult, DisabledCase, Exchange, HookResult, StatusValue, StepResult } from "../types";

export type CaseState = StatusValue | "running" | "queued" | "disabled";
export type Filter = "notpass" | "all" | "fail" | "error" | "blocked" | "skipped" | "pass" | "disabled";
export type Delta = "new" | "still" | "fixed";
export type GroupBy = "folder" | "cause" | "status" | "label" | "none";
export type SortBy = "suite" | "time" | "key" | "name";
export type Change = "new" | "still" | "fixed" | "same" | "added";

export const NOT_PASSED: CaseState[] = ["fail", "error", "blocked", "skipped"];

export const LABELS = [
  { id: "bug", label: "Bug", hint: "Product bug", key: "1" },
  { id: "test", label: "Test", hint: "The test needs fixing", key: "2" },
  { id: "env", label: "Env", hint: "Environment or data", key: "3" },
  { id: "known", label: "Known", hint: "Known or expected", key: "4" },
  { id: "flaky", label: "Flaky", hint: "Passes and fails at random", key: "5" },
] as const;

export function labelName(id?: string): string {
  if (!id) return "";
  const l = LABELS.find((x) => x.id === id);
  if (l) return l.label;
  // labels written by earlier versions
  return ({ defect: "Bug", environment: "Env", requirement: "Test" } as Record<string, string>)[id] ?? id;
}

export function labelClass(id?: string): string {
  const name = labelName(id).toLowerCase();
  return LABELS.some((l) => l.label.toLowerCase() === name) ? name : "known";
}

export const PHASE_NAMES: Record<string, string> = {
  pre: "Precondition", step: "Step", cleanup: "Cleanup", setup: "Setup", teardown: "Teardown",
};

export function stepTitle(s: { phase: string; index: number }): string {
  return `${PHASE_NAMES[s.phase] ?? s.phase} ${s.index + 1}`;
}

// ---------------------------------------------------------------- failures

export interface Failure {
  at?: { phase: string; index: number; name: string };
  kind: "check" | "status" | "diff" | "error" | "blocked" | "skipped" | "other";
  expr?: string;
  expected?: string;
  actual?: string;
  message: string;
  /** How many more checks or differences failed in the same step. */
  more: number;
  http?: number;
  /** A short error text taken from the response body, for server errors. */
  hint?: string;
}

/** Splits "expected X, got Y" into its two values. */
export function expectedActual(message?: string): { expected?: string; actual?: string } {
  if (!message || !message.startsWith("expected ")) return {};
  const i = message.lastIndexOf(", got ");
  if (i < 0) return {};
  return { expected: message.slice(9, i), actual: message.slice(i + 6) };
}

export function failureOf(c: CaseResult): Failure | undefined {
  if (c.status === "pass") return undefined;
  const s = firstFailure(c);
  if (!s) {
    const kind = c.status === "blocked" ? "blocked" : c.status === "skipped" ? "skipped" : "other";
    return { kind, message: c.reason ?? c.status, more: 0 };
  }
  return stepFailure(s);
}

export function stepFailure(s: StepResult): Failure | undefined {
  if (s.status === "pass") return undefined;
  const at = { phase: s.phase, index: s.index, name: s.name };
  const http = s.target?.status;
  const failed = (s.assertions ?? []).filter((a) => !a.pass);
  const hint = http && http >= 400 ? bodyHint(s.target) : undefined;
  if (s.status === "skipped") return { at, kind: "skipped", message: s.reason ?? "Skipped", more: 0, http };
  if (s.status === "error" && !failed.length) {
    return { at, kind: "error", message: s.reason ?? s.target?.error ?? "Error", more: 0, http, hint };
  }
  if (failed.length) {
    const a = failed[0];
    const ea = expectedActual(a.message);
    return {
      at, kind: /^status\b/.test(a.expr) ? "status" : "check", expr: a.expr, ...ea, message: a.message ?? "",
      more: failed.length - 1 + (s.diffs?.length ?? 0), http, hint,
    };
  }
  if (s.diffs?.length) return { at, kind: "diff", message: s.diffs[0], more: s.diffs.length - 1, http };
  return { at, kind: "other", message: s.reason ?? s.status, more: 0, http, hint };
}

const HINT_KEYS = ["message", "error", "errorMessage", "error_description", "detail", "title", "reason"];

/** The error text a failed response carries, if it has one (message, error, detail...). */
export function bodyHint(ex?: Exchange): string | undefined {
  const body = ex?.responseBody;
  if (!body || body.length > 200_000) return undefined;
  const t = body.trim();
  if (!t.startsWith("{")) return t.length < 160 && !t.startsWith("<") ? t : undefined;
  try {
    const o = JSON.parse(t) as Record<string, unknown>;
    for (const k of HINT_KEYS) {
      const v = o[k];
      if (typeof v === "string" && v.trim()) return v.trim().slice(0, 200);
      if (v && typeof v === "object" && typeof (v as Record<string, unknown>).message === "string") {
        return String((v as Record<string, unknown>).message).slice(0, 200);
      }
    }
  } catch {
    /* not json */
  }
  return undefined;
}

/** Two values with their shared start shortened to "…", so the part that differs stands out. */
export function trimCommon(a: string, b: string, keep = 4): [string, string] {
  if (Math.max(a.length, b.length) <= 28) return [a, b];
  let p = 0;
  while (p < a.length && p < b.length && a[p] === b[p]) p++;
  if (p <= keep + 8) return [a, b];
  const quote = a.startsWith('"') && b.startsWith('"') ? '"' : "";
  return [quote + "…" + a.slice(p - keep), quote + "…" + b.slice(p - keep)];
}

/** A check's path without the leading "$." for tight spaces. */
export function shortPath(p: string): string {
  return p.startsWith("$.") ? p.slice(2) : p;
}

/** The JSON path a check reads, as $.a.b[0].c, or null when it reads several values or a header. */
export function checkPath(expr: string): string | null {
  const m = /^\$[^\s]*/.exec(expr.trim());
  if (!m) return null;
  let p = m[0];
  if (/\[\*]|\[\?|\.\.|\*/.test(p)) return null;
  p = p.replace(/\['([^']+)']/g, ".$1").replace(/\["([^"]+)"]/g, ".$1");
  p = p.replace(/\.length$/, "").replace(/\.(size|count)\(\)$/, "");
  return p === "$" ? "$" : p;
}

// ---------------------------------------------------------------- one entry per test case

export interface CaseInfo {
  id: string;
  key: string;
  name: string;
  path: string[];
  folderId?: string;
  tags: string[];
  state: CaseState;
  result?: CaseResult;
  disabled?: DisabledCase;
  attempts: number;
  change?: Change;
  failure?: Failure;
  http?: number;
  durationMs?: number;
  label?: string;
  note?: string;
  order: number;
}

export function caseState(run: RunView, id: string): CaseState {
  if (run.running[id]) return "running";
  if (run.pending?.[id]) return "queued";
  const r = run.cases[id];
  if (r) return r.status;
  return "queued";
}

export function changeOf(state: CaseState, prev?: string): Change | undefined {
  if (state === "disabled" || state === "running" || state === "queued") return undefined;
  if (prev === undefined) return "added";
  const was = prev === "pass";
  const now = state === "pass";
  if (was && now) return "same";
  if (was && !now) return "new";
  if (!was && now) return "fixed";
  return "still";
}

/** Every test case of the run in suite order, disabled ones where they sit. */
export function caseInfos(run: RunView, prev?: Record<string, string>): CaseInfo[] {
  const ids = caseOrder(run);
  const disabled = (run.header.disabled ?? []).filter((d) => !run.cases[d.caseId]);
  const byPos = new Map<number, DisabledCase[]>();
  for (const d of disabled) {
    const p = Math.min(d.position ?? ids.length, ids.length);
    byPos.set(p, [...(byPos.get(p) ?? []), d]);
  }
  const out: CaseInfo[] = [];
  const push = (info: Omit<CaseInfo, "order">) => out.push({ ...info, order: out.length });
  const addDisabled = (p: number) => {
    for (const d of byPos.get(p) ?? []) {
      push({ id: d.caseId, key: d.key, name: d.name, path: d.path ?? [], folderId: d.folderId, tags: [], state: "disabled", disabled: d, attempts: 0 });
    }
  };
  ids.forEach((id, i) => {
    addDisabled(i);
    const r = run.cases[id];
    const state = caseState(run, id);
    const failure = r ? failureOf(r) : undefined;
    push({
      id, key: r?.key ?? id, name: r?.name ?? "", path: r?.path ?? [], folderId: r?.folderId, tags: r?.tags ?? [],
      state, result: r, attempts: (run.history[id]?.length ?? 0) + (r ? 1 : 0), change: prev ? changeOf(state, prev[id]) : undefined,
      failure, http: failure?.http ?? lastHttp(r), durationMs: r?.durationMs, label: r?.triage?.label, note: r?.triage?.note,
    });
  });
  addDisabled(ids.length);
  return out;
}

function lastHttp(r?: CaseResult): number | undefined {
  const steps = r?.steps ?? [];
  for (let i = steps.length - 1; i >= 0; i--) if (steps[i].target?.status) return steps[i].target!.status;
  return undefined;
}

/** Test cases whose names are not in the run (queued ones of a live run) get them from the suite. */
export function withNames(infos: CaseInfo[], lookup: (id: string) => { key: string; name: string; path: string[]; folderId?: string } | null): CaseInfo[] {
  if (!infos.some((i) => !i.name && !i.disabled)) return infos;
  return infos.map((i) => {
    if (i.name || i.disabled) return i;
    const l = lookup(i.id);
    return l ? { ...i, key: l.key, name: l.name, path: l.path, folderId: l.folderId } : i;
  });
}

// ---------------------------------------------------------------- counting

export type Counts = Record<CaseState | "all" | "notpass", number>;

export function countStates(infos: CaseInfo[]): Counts {
  const c: Counts = { all: 0, notpass: 0, pass: 0, fail: 0, error: 0, blocked: 0, skipped: 0, running: 0, queued: 0, disabled: 0 };
  for (const i of infos) {
    c.all++;
    c[i.state]++;
    if (NOT_PASSED.includes(i.state)) c.notpass++;
  }
  return c;
}

export function countChanges(infos: CaseInfo[]): Record<Change, number> {
  const c: Record<Change, number> = { new: 0, still: 0, fixed: 0, same: 0, added: 0 };
  for (const i of infos) if (i.change) c[i.change]++;
  return c;
}

export interface Stats {
  total: number;
  run: number;
  counts: Counts;
  changes: Record<Change, number>;
  durationMs: number;
  labelled: number;
}

export function statsOf(infos: CaseInfo[]): Stats {
  const counts = countStates(infos);
  let durationMs = 0;
  let labelled = 0;
  for (const i of infos) {
    durationMs += i.durationMs ?? 0;
    if (i.label) labelled++;
  }
  return { total: infos.length, run: infos.length - counts.disabled, counts, changes: countChanges(infos), durationMs, labelled };
}

// ---------------------------------------------------------------- matching

export function matchesFilter(i: CaseInfo, filter: Filter, delta?: Delta): boolean {
  if (delta && i.change !== delta) return false;
  if (filter === "all") return true;
  if (filter === "notpass") return NOT_PASSED.includes(i.state);
  return i.state === filter;
}

/** Search words match the key, name, folders, reason, label and note; with bodies also requests and responses. */
export function searcher(query: string, bodies: boolean): ((i: CaseInfo) => boolean) | null {
  const tags: string[] = [];
  const text = query.toLowerCase().replace(/(?:^|\s)(?:#|tag:)(\S+)/g, (_, t: string) => {
    tags.push(t);
    return " ";
  }).trim();
  if (!text && !tags.length) return null;
  const words = text ? text.split(/\s+/) : [];
  return (i) => {
    if (tags.length && !tags.every((t) => i.tags.some((x) => x.toLowerCase().includes(t)))) return false;
    if (!words.length) return true;
    const r = i.result;
    const hay = `${i.key} ${i.name} ${i.path.join("/")} ${r?.reason ?? ""} ${labelName(i.label)} ${i.note ?? ""} ${i.failure?.message ?? ""}`.toLowerCase();
    if (words.every((w) => hay.includes(w))) return true;
    if (!bodies || !r?.steps?.length) return false;
    const deep = deepText(r).toLowerCase();
    return words.every((w) => hay.includes(w) || deep.includes(w));
  };
}

const deepCache = new WeakMap<CaseResult, string>();

function deepText(r: CaseResult): string {
  let t = deepCache.get(r);
  if (t !== undefined) return t;
  const parts: string[] = [];
  for (const s of r.steps ?? []) {
    for (const ex of [s.target, s.baseline]) {
      if (!ex) continue;
      parts.push(ex.url ?? "", ex.requestBody ?? "", ex.responseBody ?? "", ex.error ?? "");
      for (const v of ex.vars ?? []) parts.push(v.name, v.value ?? "");
    }
    for (const a of s.assertions ?? []) parts.push(a.expr, a.message ?? "");
  }
  t = parts.join("\n");
  deepCache.set(r, t);
  return t;
}

// ---------------------------------------------------------------- rows

export type Row =
  | {
    kind: "group"; id: string; level: number; by: GroupBy; label: string; open: boolean; canOpen: boolean; stats: Stats;
    /** Test cases under the group that match the filter, for the group's checkbox. */
    ids: string[]; path?: string[]; folderId?: string; state?: CaseState; sample?: CaseInfo; badHooks?: HookResult[];
  }
  | { kind: "hook"; id: string; level: number; hook: HookResult; blocked: string[]; open: boolean }
  | { kind: "case"; id: string; level: number; info: CaseInfo };

export interface RowOptions {
  filter: Filter;
  delta?: Delta;
  group: GroupBy;
  sort: SortBy;
  search: string;
  searchBodies: boolean;
  open: Record<string, boolean>;
}

const STATE_ORDER: CaseState[] = ["fail", "error", "blocked", "skipped", "running", "queued", "pass", "disabled"];
const STATE_LABEL: Record<CaseState, string> = {
  fail: "Failed", error: "Error", blocked: "Blocked", skipped: "Skipped", running: "Running", queued: "Waiting", pass: "Passed", disabled: "Disabled",
};
export function stateLabel(s: CaseState): string {
  return STATE_LABEL[s];
}

function sorter(sort: SortBy): ((a: CaseInfo, b: CaseInfo) => number) | null {
  if (sort === "time") return (a, b) => (b.durationMs ?? -1) - (a.durationMs ?? -1) || a.order - b.order;
  if (sort === "key") return (a, b) => a.key.localeCompare(b.key, undefined, { numeric: true });
  if (sort === "name") return (a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }) || a.order - b.order;
  return null;
}

function sorted(list: CaseInfo[], sort: SortBy): CaseInfo[] {
  const s = sorter(sort);
  return s ? [...list].sort(s) : list;
}

/** Blocked test cases that a failed setup stopped, by the setup's key. */
function blockedBySetup(infos: CaseInfo[], hooks: HookResult[]): Map<string, string[]> {
  const out = new Map<string, string[]>();
  for (const h of hooks) {
    if (h.hook !== "setup" || h.status === "pass") continue;
    const p = h.path ?? [];
    const ids = infos.filter((i) => i.state === "blocked" && /^Setup of /.test(i.result?.reason ?? "")
      && p.every((x, k) => i.path[k] === x)).map((i) => i.id);
    if (ids.length) out.set(hookKey(h), ids);
  }
  return out;
}

interface FolderNode {
  name: string;
  path: string[];
  key: string;
  folderId?: string;
  seq: (CaseInfo | FolderNode)[];
  children: Map<string, FolderNode>;
  all: CaseInfo[];
}

function folderTree(infos: CaseInfo[], hooks: HookResult[]): FolderNode {
  const root: FolderNode = { name: "", path: [], key: "", seq: [], children: new Map(), all: [] };
  const ids = new Map<string, string>();
  for (const h of hooks) if (h.folderId) ids.set((h.path ?? []).join("\u0001"), h.folderId);
  for (const i of infos) if (i.folderId) ids.set(i.path.join("\u0001"), i.folderId);
  for (const i of infos) {
    let node = root;
    root.all.push(i);
    for (let d = 0; d < i.path.length; d++) {
      const name = i.path[d];
      let child = node.children.get(name);
      if (!child) {
        const path = i.path.slice(0, d + 1);
        const key = path.join("\u0001");
        child = { name, path, key, folderId: ids.get(key), seq: [], children: new Map(), all: [] };
        node.children.set(name, child);
        node.seq.push(child);
      }
      child.all.push(i);
      node = child;
    }
    node.seq.push(i);
  }
  return root;
}

/** The rows of the list: groups with their totals, failed setups and teardowns, and the test cases that match. */
export function buildRows(run: RunView, infos: CaseInfo[], o: RowOptions): Row[] {
  const search = searcher(o.search, o.searchBodies);
  const match = (i: CaseInfo) => matchesFilter(i, o.filter, o.delta) && (!search || search(i));
  const narrowed = !!search || !!o.delta;
  const rows: Row[] = [];
  const hooks = latestHooks(run);
  const showHook = (h: HookResult) => h.status !== "pass" && (o.filter === "all" || o.filter === "notpass" || o.filter === h.status) && !o.delta
    && (!search || `${(h.path ?? []).join("/")} ${h.hook} ${h.reason ?? ""}`.toLowerCase().includes(o.search.toLowerCase()));

  if (o.group === "folder") {
    const blocked = blockedBySetup(infos, hooks);
    const underHook = new Set([...blocked.values()].flat());
    const hooksAt = new Map<string, HookResult[]>();
    for (const h of hooks) {
      const k = (h.path ?? []).join("\u0001");
      hooksAt.set(k, [...(hooksAt.get(k) ?? []), h]);
    }
    const emitHook = (h: HookResult, level: number) => {
      const id = hookKey(h);
      const ids = blocked.get(id) ?? [];
      const open = o.open[id] ?? false;
      rows.push({ kind: "hook", id, level, hook: h, blocked: ids, open });
      if (open) {
        for (const i of infos) if (ids.includes(i.id) && match(i)) rows.push({ kind: "case", id: i.id, level: level + 1, info: i });
      }
    };
    const emit = (node: FolderNode, level: number) => {
      const own = hooksAt.get(node.key) ?? [];
      const bad = own.filter(showHook);
      const matched = node.all.filter(match);
      const visibleMatched = matched.filter((i) => !underHook.has(i.id) || bad.some((h) => (blocked.get(hookKey(h)) ?? []).includes(i.id)));
      const hasRows = visibleMatched.length > 0 || bad.length > 0 || descendantHooks(node, hooksAt, showHook);
      const top = level === 0 && node.path.length > 0;
      if (node.path.length) {
        if (!hasRows && !(top && !narrowed)) return;
        const id = "f:" + node.key;
        const open = hasRows && (o.open[id] ?? true);
        rows.push({
          kind: "group", id, level, by: "folder", label: node.name, open, canOpen: hasRows, stats: statsOf(node.all),
          ids: matched.map((i) => i.id), path: node.path, folderId: node.folderId,
          badHooks: own.filter((h) => h.status !== "pass"),
        });
        if (!open) return;
      }
      const inner = node.path.length ? level + 1 : level;
      for (const h of bad) if (h.hook === "setup") emitHook(h, inner);
      const direct = node.seq.filter((x): x is CaseInfo => "id" in x && !underHook.has(x.id) && match(x));
      const folders = node.seq.filter((x): x is FolderNode => !("id" in x));
      if (o.sort === "suite") {
        for (const x of node.seq) {
          if ("id" in x) {
            if (!underHook.has(x.id) && match(x)) rows.push({ kind: "case", id: x.id, level: inner, info: x });
          } else emit(x, inner);
        }
      } else {
        for (const i of sorted(direct, o.sort)) rows.push({ kind: "case", id: i.id, level: inner, info: i });
        for (const f of folders) emit(f, inner);
      }
      for (const h of bad) if (h.hook === "teardown") emitHook(h, inner);
    };
    emit(folderTree(infos, hooks), 0);
    return rows;
  }

  const matched = infos.filter(match);
  if (o.group === "none") {
    for (const i of sorted(matched, o.sort)) rows.push({ kind: "case", id: i.id, level: 0, info: i });
    return rows;
  }

  const groups = new Map<string, { label: string; list: CaseInfo[]; state?: CaseState; order: number }>();
  const put = (key: string, label: string, i: CaseInfo, order: number, state?: CaseState) => {
    const g = groups.get(key) ?? { label, list: [], state, order };
    g.list.push(i);
    groups.set(key, g);
  };
  for (const i of matched) {
    if (o.group === "status") put("s:" + i.state, STATE_LABEL[i.state], i, STATE_ORDER.indexOf(i.state), i.state);
    else if (o.group === "label") {
      const k = i.label ? labelName(i.label) : "";
      const idx = LABELS.findIndex((l) => l.label === k);
      put("l:" + (k || "none"), k || "No label", i, idx < 0 ? (k ? 50 : 99) : idx);
    } else {
      if (i.state === "pass") put("c:pass", "Passed", i, 1000, "pass");
      else if (i.state === "disabled") put("c:disabled", "Disabled", i, 1001, "disabled");
      else if (i.state === "running" || i.state === "queued") put("c:" + i.state, STATE_LABEL[i.state], i, 999, i.state);
      else put("c:" + failureSignature(i.result!), causeLabel(i), i, 0, i.state);
    }
  }
  const list = [...groups.entries()].map(([id, g]) => ({ id, ...g, stats: statsOf(g.list) }));
  list.sort((a, b) => a.order - b.order
    || (o.group === "cause" ? (b.stats.changes.new > 0 ? 1 : 0) - (a.stats.changes.new > 0 ? 1 : 0) || b.list.length - a.list.length : 0));
  for (const g of list) {
    const closedByDefault = g.state === "pass" || g.state === "disabled";
    const open = o.open[g.id] ?? !closedByDefault;
    rows.push({
      kind: "group", id: g.id, level: 0, by: o.group, label: g.label, open, canOpen: true, stats: g.stats, ids: g.list.map((i) => i.id),
      state: g.state, sample: g.list[0],
    });
    if (open) for (const i of sorted(g.list, o.sort)) rows.push({ kind: "case", id: i.id, level: 1, info: i });
  }
  return rows;
}

function descendantHooks(node: FolderNode, hooksAt: Map<string, HookResult[]>, show: (h: HookResult) => boolean): boolean {
  for (const c of node.children.values()) {
    if ((hooksAt.get(c.key) ?? []).some(show) || descendantHooks(c, hooksAt, show)) return true;
  }
  return false;
}

/** A readable name for a group of failures that share a cause. */
export function causeLabel(i: CaseInfo): string {
  const f = i.failure;
  const r = i.result;
  if (!f) return r?.reason ?? stateLabel(i.state);
  if (f.kind === "blocked" || f.kind === "skipped" || !f.at) return shortReason(f.message);
  const step = r?.steps?.find((s) => s.phase === f.at!.phase && s.index === f.at!.index);
  const where = step?.target?.method && step.target.url ? `${step.target.method} ${pathOf(step.target.url).replace(/\/\d+(?=\/|$)/g, "/{id}")}` : stepTitle(f.at);
  return `${where} · ${failureText(f)}`;
}

export function pathOf(url: string): string {
  return url.replace(/^https?:\/\/[^/]+/, "").replace(/\?.*$/, "") || "/";
}

export function shortReason(text: string): string {
  return text
    .replace(/^Setup of folder '([^']+)' failed at required step (\d+) '([^']+)': /, "Setup of $1 failed at step $2 “$3”: ")
    .replace(/status (?:==|is) ?\S* -> expected (\S+), got (\d+)/, "HTTP $2 (expected $1)")
    .replace(/status < (\d+) -> got (\d+)/, "HTTP $2 (expected below $1)");
}

/** One line saying what failed: the check with the value it got and the one expected, the HTTP status, or the error. */
export function failureText(f: Failure): string {
  if (f.kind === "status") {
    const got = f.actual ?? f.http;
    return `HTTP ${got ?? "?"}${f.expected ? ` (expected ${f.expected})` : ""}${f.hint ? ` · ${f.hint}` : ""}`;
  }
  if (f.kind === "check" && f.expr) {
    const path = shortPath(/^\S+/.exec(f.expr)?.[0] ?? f.expr);
    if (f.expected !== undefined && f.actual !== undefined) {
      const [e, a] = trimCommon(f.expected, f.actual);
      return `${path} got ${a}, expected ${e}`;
    }
    return `${f.expr} · ${f.message}`;
  }
  if (f.kind === "error" && f.hint) return `${f.message} · ${f.hint}`;
  return shortReason(f.message);
}

// ---------------------------------------------------------------- copying

export function listText(infos: CaseInfo[]): string {
  const lines: string[] = [];
  for (const i of infos) {
    const head = `${stateLabel(i.state).toUpperCase()}  ${i.key}  ${i.name}`;
    lines.push(head);
    if (i.failure) {
      const at = i.failure.at ? `${stepTitle(i.failure.at)} “${i.failure.at.name}”: ` : "";
      lines.push(`    ${at}${failureText(i.failure)}`);
    } else if (i.state === "disabled") lines.push(`    ${i.disabled?.folder ? `Folder “${i.disabled.folder}” is disabled` : "Disabled"}`);
    if (i.label || i.note) lines.push(`    ${[labelName(i.label), i.note].filter(Boolean).join(": ")}`);
  }
  return lines.join("\n");
}

export function tableText(infos: CaseInfo[]): string {
  const cell = (s: unknown) => String(s ?? "").replace(/[\t\r\n]+/g, " ");
  const head = ["Key", "Test case", "Folder", "Status", "Failed at", "What failed", "HTTP", "Time (ms)", "Change", "Label", "Note"];
  const rows = infos.map((i) => [
    i.key, i.name, i.path.join(" / "), stateLabel(i.state), i.failure?.at ? `${stepTitle(i.failure.at)} ${i.failure.at.name}` : "",
    i.failure ? failureText(i.failure) : "", i.http ?? "", i.durationMs ?? "", i.change && i.change !== "same" && i.change !== "added" ? i.change : "",
    labelName(i.label), i.note ?? "",
  ].map(cell).join("\t"));
  return [head.join("\t"), ...rows].join("\n");
}

// ---------------------------------------------------------------- requests as commands

export function toCurl(ex: Exchange, shell: "bash" | "cmd"): string {
  const q = shell === "bash"
    ? (s: string) => `'${s.replace(/'/g, "'\\''")}'`
    : (s: string) => `"${s.replace(/"/g, '\\"')}"`;
  const nl = shell === "bash" ? " \\\n  " : " ^\n  ";
  const parts = [`curl -X ${ex.method ?? "GET"} ${q(ex.url ?? "")}`];
  for (const h of ex.requestHeaders ?? []) {
    if (/^(content-length|host|connection)$/i.test(h.key)) continue;
    parts.push(`-H ${q(`${h.key}: ${h.value}`)}`);
  }
  if (ex.requestBody) parts.push(`--data-raw ${q(ex.requestBody)}`);
  return parts.join(nl);
}

// ---------------------------------------------------------------- pretty JSON with paths

export interface JsonLine {
  depth: number;
  key?: string;
  value?: string;
  vtype?: "str" | "num" | "bool" | "null";
  open?: string;
  close?: string;
  comma: boolean;
  path: string;
  count?: number;
}

/** A JSON value as indented lines, each knowing the path of the value it shows. */
export function jsonLines(v: unknown, limit = 200_000): JsonLine[] {
  const out: JsonLine[] = [];
  const walk = (x: unknown, path: string, key: string | undefined, depth: number, comma: boolean) => {
    if (out.length > limit) return;
    if (x !== null && typeof x === "object") {
      const arr = Array.isArray(x);
      const entries: [string | number, unknown][] = arr ? (x as unknown[]).map((y, i) => [i, y]) : Object.entries(x as Record<string, unknown>);
      if (!entries.length) {
        out.push({ depth, key, open: arr ? "[]" : "{}", comma, path });
        return;
      }
      out.push({ depth, key, open: arr ? "[" : "{", comma: false, path, count: entries.length });
      entries.forEach(([k, y], i) => {
        walk(y, typeof k === "number" ? `${path}[${k}]` : `${path}.${k}`, typeof k === "number" ? undefined : k, depth + 1, i < entries.length - 1);
      });
      out.push({ depth, close: arr ? "]" : "}", comma, path: path + "#end" });
      return;
    }
    const vtype = x === null ? "null" : typeof x === "string" ? "str" : typeof x === "number" ? "num" : typeof x === "boolean" ? "bool" : "str";
    out.push({ depth, key, value: JSON.stringify(x), vtype, comma, path });
  };
  walk(v, "$", undefined, 0, false);
  return out;
}

export function lineText(l: JsonLine): string {
  return `${"  ".repeat(l.depth)}${l.key !== undefined ? JSON.stringify(l.key) + ": " : ""}${l.value ?? l.open ?? l.close ?? ""}${l.comma ? "," : ""}`;
}
