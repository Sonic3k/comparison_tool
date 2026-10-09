import { create } from "zustand";
import { api } from "./api";
import { idbSet, pref, setPref, writeInFolder } from "./files";
import type { RunView } from "./runs";
import { emptyRun, serializeRun } from "./runs";
import type { Delta, Filter, GroupBy, SortBy } from "./results/model";
import type { PastRun } from "./results/history";
import type { Phase } from "./suiteOps";
import { allCases, find } from "./suiteOps";
import type { CaseResult, HookResult, Issue, RunHeader, RunOptions, RunSummary, Selection, StatusValue, StepResult, Suite } from "./types";

export type { Filter } from "./results/model";
export type View = "edit" | "runs";
export interface Sel {
  kind: "suite" | "folder" | "case";
  id?: string;
  tab?: string;
  phase?: Phase;
  step?: number;
}
/** What the results screen shows in its detail panel: a test case or a hook, one of its steps (phase:index) and a tab. */
export interface RunSel {
  caseId?: string;
  hook?: string;
  step?: string;
  tab?: string;
  /** An earlier attempt of the test case (1 = first); unset shows the latest. */
  attempt?: number;
}
export interface Toast {
  id: number;
  kind: "info" | "ok" | "error";
  text: string;
  action?: { label: string; run: () => void };
}
export type ColumnId = "failedAt" | "what" | "http" | "time" | "change" | "label";
export const COLUMNS: { id: ColumnId; label: string }[] = [
  { id: "failedAt", label: "Failed at" },
  { id: "what", label: "What failed" },
  { id: "http", label: "HTTP" },
  { id: "time", label: "Time" },
  { id: "change", label: "vs previous" },
  { id: "label", label: "Label" },
];

interface State {
  suite: Suite | null;
  fileName: string | null;
  handle: unknown;
  serverFile: string | null;
  format: string | null;
  dirty: boolean;
  past: Suite[];
  future: Suite[];
  issues: Issue[];
  notes: string[];
  view: View;
  sel: Sel;
  multi: string[];
  expanded: Record<string, boolean>;
  env?: string;
  baselineEnv?: string;
  runs: RunView[];
  activeRun?: string;
  runSel: RunSel;
  filter: Filter;
  delta?: Delta;
  group: GroupBy;
  sortBy: SortBy;
  search: string;
  searchBodies: boolean;
  groupOpen: Record<string, boolean>;
  checked: Record<string, true>;
  dock: "bottom" | "right";
  detailSize: number;
  maximized: boolean;
  columns: Record<ColumnId, boolean>;
  pastRuns: PastRun[] | null;
  pastSuite?: string;
  compareWith: string;
  /** The test case the editor was opened from on the results screen, for the way back. */
  fromResults?: { runId: string; caseId?: string; label: string };
  treeSearch: string;
  toasts: Toast[];
  resultsDir: unknown;
  resultsDirName?: string;
  theme: "light" | "dark";
  tryResults: Record<string, StepResult>;
  tryVars: Record<string, string>;

  load(suite: Suite, info: { fileName: string | null; handle?: unknown; serverFile?: string | null; format?: string; notes?: string[]; issues?: Issue[] }): void;
  close(): void;
  edit(fn: (s: Suite) => Suite, coalesce?: string): void;
  undo(): void;
  redo(): void;
  markSaved(handle?: unknown, fileName?: string, format?: string): void;
  select(sel: Sel, opts?: { keepMulti?: boolean }): void;
  setMulti(ids: string[]): void;
  toggleExpanded(id: string, open?: boolean): void;
  expandMany(ids: string[], open: boolean): void;
  setView(v: View): void;
  setEnv(env?: string, baseline?: string): void;
  toast(kind: Toast["kind"], text: string, action?: Toast["action"]): void;
  dropToast(id: number): void;
  startRun(selection?: Selection, options?: RunOptions): Promise<void>;
  stopRun(runId: string): Promise<void>;
  addRun(run: RunView): void;
  removeRun(runId: string): void;
  setActiveRun(runId?: string): void;
  selectRun(sel: RunSel): void;
  setFilter(f: Filter): void;
  setDelta(d?: Delta): void;
  setGroup(g: GroupBy): void;
  setSort(s: SortBy): void;
  setSearch(s: string): void;
  setSearchBodies(on: boolean): void;
  setGroupOpen(ids: string[], open: boolean | undefined): void;
  setChecked(ids: string[], on: boolean): void;
  clearChecked(): void;
  setDock(d: "bottom" | "right"): void;
  setDetailSize(px: number): void;
  setMaximized(on: boolean): void;
  setColumn(id: ColumnId, on: boolean): void;
  setCompareWith(pick: string): void;
  setTreeSearch(s: string): void;
  setTriage(runId: string, caseIds: string[], change: { label?: string | null; note?: string | null }): void;
  markRunSaved(runId: string, name: string, serverName?: string): void;
  setResultsDir(dir: unknown, name?: string): void;
  setTheme(t: "light" | "dark"): void;
  setTryResult(key: string, r: StepResult | undefined): void;
  setTryVars(v: Record<string, string>): void;
  validate(): Promise<void>;
}

let toastSeq = 1;
let validateTimer: number | undefined;
let draftTimer: number | undefined;
let lastEdit = { key: "", at: 0 };

const DEFAULT_COLUMNS: Record<ColumnId, boolean> = { failedAt: true, what: true, http: true, time: true, change: true, label: true };

export const useStore = create<State>((set, get) => ({
  suite: null,
  fileName: null,
  handle: null,
  serverFile: null,
  format: null,
  dirty: false,
  past: [],
  future: [],
  issues: [],
  notes: [],
  view: "edit",
  sel: { kind: "suite", tab: "general" },
  multi: [],
  expanded: {},
  runs: [],
  runSel: {},
  filter: "notpass",
  group: pref<GroupBy>("results.group", "folder"),
  sortBy: "suite",
  search: "",
  searchBodies: pref<boolean>("results.searchBodies", false),
  groupOpen: {},
  checked: {},
  dock: pref<"bottom" | "right">("results.dock", "bottom"),
  detailSize: pref<number>("results.detailSize", 0),
  maximized: false,
  columns: { ...DEFAULT_COLUMNS, ...pref<Partial<Record<ColumnId, boolean>>>("results.columns", {}) },
  pastRuns: null,
  compareWith: "auto",
  treeSearch: "",
  toasts: [],
  resultsDir: null,
  theme: pref<"light" | "dark">("theme", "light"),
  tryResults: {},
  tryVars: {},

  load(suite, info) {
    const envNames = (suite.environments ?? []).map((e) => e.name);
    const env = suite.settings?.env && envNames.includes(suite.settings.env) ? suite.settings.env : envNames[0];
    const baseline = suite.settings?.baselineEnv && envNames.includes(suite.settings.baselineEnv) ? suite.settings.baselineEnv : undefined;
    const expanded: Record<string, boolean> = {};
    const top = suite.items ?? [];
    top.forEach((n) => {
      if (n.type === "folder") expanded[n.id] = top.length <= 12;
    });
    lastEdit = { key: "", at: 0 };
    set({
      suite,
      fileName: info.fileName,
      handle: info.handle ?? null,
      serverFile: info.serverFile ?? null,
      format: info.format ?? "v2",
      dirty: info.format === "v1",
      past: [],
      future: [],
      issues: info.issues ?? [],
      notes: info.notes ?? [],
      sel: { kind: "suite", tab: "general" },
      multi: [],
      expanded,
      env,
      baselineEnv: baseline,
      view: "edit",
      treeSearch: "",
      tryResults: {},
      fromResults: undefined,
    });
    if (!info.issues) get().validate();
  },

  close() {
    set({ suite: null, fileName: null, handle: null, serverFile: null, format: null, dirty: false, past: [], future: [], issues: [], notes: [], multi: [], sel: { kind: "suite", tab: "general" } });
    idbSet("draft", null);
  },

  edit(fn, coalesce) {
    const { suite, past } = get();
    if (!suite) return;
    const next = fn(suite);
    if (next === suite) return;
    const now = Date.now();
    const merge = !!coalesce && coalesce === lastEdit.key && now - lastEdit.at < 1500 && past.length > 0;
    lastEdit = { key: coalesce ?? "", at: now };
    set({ suite: next, past: merge ? past : [...past.slice(-79), suite], future: [], dirty: true });
    window.clearTimeout(validateTimer);
    validateTimer = window.setTimeout(() => get().validate(), 800);
    window.clearTimeout(draftTimer);
    draftTimer = window.setTimeout(() => {
      const st = get();
      if (st.suite && st.dirty && !st.serverFile) idbSet("draft", { suite: st.suite, fileName: st.fileName, format: st.format, handle: st.handle ?? null, at: Date.now() });
    }, 1500);
  },

  undo() {
    const { past, suite, future } = get();
    if (!past.length || !suite) return;
    lastEdit = { key: "", at: 0 };
    set({ suite: past[past.length - 1], past: past.slice(0, -1), future: [suite, ...future].slice(0, 80), dirty: true });
    window.clearTimeout(validateTimer);
    validateTimer = window.setTimeout(() => get().validate(), 400);
  },

  redo() {
    const { past, suite, future } = get();
    if (!future.length || !suite) return;
    lastEdit = { key: "", at: 0 };
    set({ suite: future[0], future: future.slice(1), past: [...past, suite], dirty: true });
    window.clearTimeout(validateTimer);
    validateTimer = window.setTimeout(() => get().validate(), 400);
  },

  markSaved(handle, fileName, format) {
    set((s) => ({ dirty: false, handle: handle === undefined ? s.handle : handle, fileName: fileName ?? s.fileName, format: format ?? "v2" }));
    idbSet("draft", null);
  },

  select(sel, opts) {
    set((s) => ({ sel, view: "edit", multi: opts?.keepMulti ? s.multi : sel.id ? [sel.id] : [] }));
  },

  setMulti(ids) {
    set({ multi: ids });
  },

  toggleExpanded(id, open) {
    set((s) => ({ expanded: { ...s.expanded, [id]: open === undefined ? !s.expanded[id] : open } }));
  },

  expandMany(ids, open) {
    set((s) => {
      const e = { ...s.expanded };
      for (const id of ids) e[id] = open;
      return { expanded: e };
    });
  },

  setView(v) {
    set((s) => ({ view: v, fromResults: v === "runs" ? undefined : s.fromResults }));
  },

  setEnv(env, baseline) {
    set({ env, baselineEnv: baseline === env ? undefined : baseline });
  },

  toast(kind, text, action) {
    const id = toastSeq++;
    set((s) => ({ toasts: [...s.toasts.slice(-2), { id, kind, text, action }] }));
    window.setTimeout(() => get().dropToast(id), action ? 8000 : kind === "error" ? 9000 : 3500);
  },

  dropToast(id) {
    set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) }));
  },

  async startRun(selection, options) {
    const { suite, env, baselineEnv } = get();
    if (!suite) return;
    const planned = plannedCount(suite, selection);
    const capture = options?.capture ?? (planned <= 60 ? "all" : "failed");
    try {
      const res = await api.startRun({ suite, env, baselineEnv, selection, options: { ...options, capture } });
      const run = emptyRun(res.header, { resultFile: res.resultFile, serverName: baseName(res.resultFile) });
      const single = selection?.caseIds?.length === 1 && !selection.folderIds?.length && !selection.tags?.length ? selection.caseIds[0] : undefined;
      set((s) => ({
        runs: [run, ...s.runs].slice(0, 20), activeRun: run.runId, view: "runs", runSel: single ? { caseId: single } : {},
        filter: planned <= 60 ? "all" : "notpass", delta: undefined, search: "", checked: {}, groupOpen: {}, maximized: false,
      }));
      poll(run.runId);
    } catch (e) {
      get().toast("error", (e as Error).message);
    }
  },

  async stopRun(runId) {
    try {
      const r = await api.stop(runId);
      get().toast("info", r.hard ? "Stopping now: cleanup and teardown are skipped" : "Stopping: running steps finish, cleanup and teardown still run. Stop again to skip them.");
    } catch (e) {
      get().toast("error", (e as Error).message);
    }
  },

  addRun(run) {
    set((s) => ({
      runs: [run, ...s.runs.filter((r) => r.runId !== run.runId)].slice(0, 20), activeRun: run.runId, view: "runs", runSel: {},
      filter: "notpass", delta: undefined, search: "", checked: {}, groupOpen: {}, maximized: false,
    }));
  },

  removeRun(runId) {
    set((s) => {
      const runs = s.runs.filter((r) => r.runId !== runId);
      return { runs, activeRun: s.activeRun === runId ? runs[0]?.runId : s.activeRun, runSel: s.activeRun === runId ? {} : s.runSel, checked: {} };
    });
  },

  setActiveRun(runId) {
    set({ activeRun: runId, runSel: {}, checked: {}, groupOpen: {}, delta: undefined, maximized: false, compareWith: "auto" });
  },

  selectRun(sel) {
    set({ runSel: sel, view: "runs" });
  },

  setFilter(f) {
    set({ filter: f });
  },

  setDelta(d) {
    set((s) => ({ delta: d, filter: d === "fixed" ? "all" : d ? (s.filter === "pass" || s.filter === "disabled" ? "notpass" : s.filter) : s.filter }));
  },

  setGroup(g) {
    set({ group: g, groupOpen: {} });
    setPref("results.group", g);
  },

  setSort(sortBy) {
    set({ sortBy });
  },

  setSearch(s) {
    set({ search: s });
  },

  setSearchBodies(on) {
    set({ searchBodies: on });
    setPref("results.searchBodies", on);
  },

  setGroupOpen(ids, open) {
    set((s) => {
      const next = { ...s.groupOpen };
      for (const id of ids) {
        if (open === undefined) delete next[id];
        else next[id] = open;
      }
      return { groupOpen: next };
    });
  },

  setChecked(ids, on) {
    set((s) => {
      const next = { ...s.checked };
      for (const id of ids) {
        if (on) next[id] = true;
        else delete next[id];
      }
      return { checked: next };
    });
  },

  clearChecked() {
    set({ checked: {} });
  },

  setDock(d) {
    set({ dock: d });
    setPref("results.dock", d);
  },

  setDetailSize(px) {
    set({ detailSize: px });
    setPref("results.detailSize", px);
  },

  setMaximized(on) {
    set({ maximized: on });
  },

  setColumn(id, on) {
    set((s) => {
      const columns = { ...s.columns, [id]: on };
      setPref("results.columns", columns);
      return { columns };
    });
  },

  setCompareWith(pick) {
    set({ compareWith: pick });
  },

  setTreeSearch(s) {
    set({ treeSearch: s });
  },

  setTriage(runId, caseIds, change) {
    set((s) => ({
      runs: s.runs.map((r) => {
        if (r.runId !== runId) return r;
        const cases = { ...r.cases };
        let touched = false;
        for (const id of caseIds) {
          const c = cases[id];
          if (!c) continue;
          const label = change.label === undefined ? c.triage?.label : change.label || undefined;
          const note = change.note === undefined ? c.triage?.note : change.note || undefined;
          const cleaned: CaseResult = { ...c, triage: label || note ? { label, note } : undefined };
          cases[id] = cleaned;
          touched = true;
        }
        return touched ? { ...r, cases, dirty: true } : r;
      }),
    }));
    scheduleSave(runId);
  },

  markRunSaved(runId, name, serverName) {
    set((s) => ({ runs: s.runs.map((r) => (r.runId === runId ? { ...r, savedTo: name, dirty: false, serverName: serverName ?? r.serverName } : r)) }));
  },

  setResultsDir(dir, name) {
    set({ resultsDir: dir, resultsDirName: name, pastRuns: null });
    idbSet("resultsDir", dir);
  },

  setTheme(t) {
    set({ theme: t });
    setPref("theme", t);
  },

  setTryResult(key, r) {
    set((s) => {
      const next = { ...s.tryResults };
      if (r) next[key] = r;
      else delete next[key];
      return { tryResults: next };
    });
  },

  setTryVars(v) {
    set({ tryVars: v });
  },

  async validate() {
    const suite = get().suite;
    if (!suite) return;
    try {
      const issues = await api.validate(suite);
      if (get().suite === suite) set({ issues });
    } catch {
      /* validation is advisory */
    }
  },
}));

function baseName(path?: string): string | undefined {
  if (!path) return undefined;
  const parts = path.split(/[\\/]/);
  return parts[parts.length - 1] || undefined;
}

/** How many test cases a selection would run (dependencies not counted), to pick the capture mode. */
function plannedCount(suite: Suite, selection?: Selection): number {
  const cases = allCases(suite).filter(({ tc, path }) => !tc.disabled && !path.some((f) => f.disabled));
  if (!selection || (!selection.caseIds?.length && !selection.folderIds?.length && !selection.tags?.length && !selection.caseKeys?.length && !selection.folderPaths?.length)) return cases.length;
  const ids = new Set(selection.caseIds ?? []);
  const folders = new Set(selection.folderIds ?? []);
  const tags = new Set(selection.tags ?? []);
  return cases.filter(({ tc, path }) => ids.has(tc.id) || path.some((f) => folders.has(f.id)) || (tc.tags ?? []).some((t) => tags.has(t))).length;
}

export function patchRun(runId: string, fn: (r: RunView) => RunView) {
  useStore.setState((s) => ({ runs: s.runs.map((r) => (r.runId === runId ? fn(r) : r)) }));
}

async function poll(runId: string) {
  let failures = 0;
  for (;;) {
    const run = useStore.getState().runs.find((r) => r.runId === runId);
    if (!run) return;
    let res;
    try {
      res = await api.events(runId, run.nextSeq);
      failures = 0;
    } catch (e) {
      if (++failures < 5) {
        await new Promise((r) => setTimeout(r, 1000 * failures));
        continue;
      }
      useStore.getState().toast("error", "Lost the run: " + (e as Error).message);
      patchRun(runId, (r) => ({ ...r, done: true, running: {} }));
      return;
    }
    if (res.events.length) {
      patchRun(runId, (r) => {
        const next: RunView = { ...r, cases: { ...r.cases }, running: { ...r.running }, hooks: [...r.hooks] };
        for (const ev of res.events) {
          if (ev.type === "started") next.header = ev.data as RunHeader;
          else if (ev.type === "caseStarted") next.running[ev.data as string] = true;
          else if (ev.type === "hook") next.hooks.push(ev.data as HookResult);
          else if (ev.type === "case") {
            const c = ev.data as CaseResult;
            next.cases[c.caseId] = c;
            delete next.running[c.caseId];
          } else if (ev.type === "finished") next.summary = ev.data as RunSummary;
        }
        next.nextSeq = res.next;
        return next;
      });
    }
    if (res.done) {
      patchRun(runId, (r) => ({ ...r, done: true, running: {} }));
      const st = useStore.getState();
      const fin = st.runs.find((r) => r.runId === runId);
      if (fin?.summary) {
        const c = fin.summary.counts;
        const bad = (c.fail ?? 0) + (c.error ?? 0) + (c.blocked ?? 0);
        const text = fin.summary.error ? `Run could not finish: ${fin.summary.error}`
          : `Run finished: ${c.pass ?? 0} passed, ${c.fail ?? 0} failed, ${c.error ?? 0} error, ${c.blocked ?? 0} blocked${c.skipped ? `, ${c.skipped} skipped` : ""}`;
        st.toast(bad || fin.summary.error ? "error" : "ok", text);
        if (st.resultsDir && !fin.resultFile) {
          const name = runFileName(fin);
          writeInFolder(st.resultsDir, name, serializeRun(fin))
            .then(() => useStore.getState().markRunSaved(runId, name))
            .catch(() => st.toast("error", "The results folder needs permission again: use Save result on the run page"));
        }
        useStore.setState({ pastRuns: null });
      }
      return;
    }
    await new Promise((r) => setTimeout(r, res.events.length ? 250 : 600));
  }
}

// ---------------------------------------------------------------- saving changes to a result

const saveTimers: Record<string, number> = {};

/** Saves a changed run back where it came from, a moment after the last change, when that needs no prompt. */
export function scheduleSave(runId: string) {
  window.clearTimeout(saveTimers[runId]);
  saveTimers[runId] = window.setTimeout(() => {
    delete saveTimers[runId];
    saveQuietly(runId);
  }, 1200);
}

export async function saveQuietly(runId: string): Promise<boolean> {
  const st = useStore.getState();
  const run = st.runs.find((r) => r.runId === runId);
  if (!run || !run.dirty || !run.done || run.rerun) return false;
  try {
    if (st.serverFile && run.serverName) {
      await api.saveResult(run.serverName, serializeRun(run));
      useStore.getState().markRunSaved(runId, run.serverName);
      return true;
    }
    const dir = st.resultsDir as { queryPermission?: (o: { mode: string }) => Promise<string> } | null;
    if (dir && run.savedTo && dir.queryPermission && (await dir.queryPermission({ mode: "readwrite" })) === "granted") {
      await writeInFolder(dir, run.savedTo, serializeRun(run));
      useStore.getState().markRunSaved(runId, run.savedTo);
      return true;
    }
  } catch {
    /* stays unsaved: Save result shows it */
  }
  return false;
}

export function runFileName(run: RunView): string {
  return `${safe(run.header.suite)}_${safe(run.header.env)}_${run.runId}.run.jsonl`;
}

export function safe(s: string): string {
  return (s || "suite").replace(/[\\/:*?"<>|\s]+/g, "_").slice(0, 80);
}

export function activeRunView(): RunView | undefined {
  const s = useStore.getState();
  return s.runs.find((r) => r.runId === s.activeRun);
}

/** Status of each test case in the most recent run that ran it. */
export function lastStatuses(runs: RunView[]): Record<string, StatusValue | "running"> {
  const out: Record<string, StatusValue | "running"> = {};
  for (const r of [...runs].reverse()) {
    for (const [id, c] of Object.entries(r.cases)) out[id] = c.status;
    for (const id of Object.keys(r.running)) out[id] = "running";
  }
  return out;
}

/** The latest result of a test case, with the run it came from. */
export function latestCaseResult(runs: RunView[], caseId: string): { run: RunView; result: CaseResult } | null {
  for (const r of runs) {
    const c = r.cases[caseId];
    if (c) return { run: r, result: c };
  }
  return null;
}

/** Values seen in recent runs (setup outputs and extracted values), newest first, for the Send button. */
export function knownValues(runs: RunView[]): Record<string, string> {
  const out: Record<string, string> = {};
  for (const r of [...runs].reverse()) {
    for (const h of r.hooks) {
      const lane = h.vars?.target ?? h.vars?.[Object.keys(h.vars ?? {})[0] ?? ""];
      if (lane) Object.assign(out, lane);
    }
    for (const c of Object.values(r.cases)) for (const s of c.steps ?? []) if (s.extracted) Object.assign(out, s.extracted);
  }
  return out;
}

export function nodeExists(suite: Suite | null, id?: string): boolean {
  return !!suite && !!id && !!find(suite, id);
}
