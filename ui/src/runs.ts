import type { CaseResult, HookResult, RunHeader, RunSummary, StatusValue, StepResult } from "./types";

/**
 * A run as the UI holds it. cases has the latest result of each test case; history the earlier attempts of the
 * test cases that were run again inside this run (oldest first). rerun is set while such a repeat is going.
 */
export interface RunView {
  runId: string;
  header: RunHeader;
  hooks: HookResult[];
  cases: Record<string, CaseResult>;
  history: Record<string, CaseResult[]>;
  running: Record<string, true>;
  pending?: Record<string, true>;
  summary?: RunSummary;
  source: "live" | "file";
  fileName?: string;
  /** Path of the result file the server writes (server-file mode). */
  resultFile?: string;
  /** Name of the result file in the server's results folder, used to save changes back. */
  serverName?: string;
  nextSeq: number;
  done: boolean;
  /** Labels, notes or test cases run again since the result was last saved. */
  dirty?: boolean;
  savedTo?: string;
  rerun?: { runId: string; nextSeq: number; caseIds: string[]; startedAt: string; hooks: string };
}

export const STATUSES: StatusValue[] = ["pass", "fail", "error", "blocked", "skipped"];

export function counts(run: RunView): Record<StatusValue | "queued" | "running", number> {
  const c = { pass: 0, fail: 0, error: 0, blocked: 0, skipped: 0, queued: 0, running: 0 };
  for (const r of Object.values(run.cases)) c[r.status]++;
  c.running = Object.keys(run.running).length;
  const planned = run.header.planned ?? run.header.caseIds?.length ?? Object.keys(run.cases).length;
  c.queued = Math.max(0, planned - Object.keys(run.cases).length - c.running);
  return c;
}

export function hookKey(h: { folderId?: string; hook: string }): string {
  return `hook:${h.folderId ?? "suite"}:${h.hook}`;
}

/** The latest result of each hook (a test case run again can run its folder's setup again). */
export function latestHooks(run: RunView): HookResult[] {
  const m = new Map<string, HookResult>();
  for (const h of run.hooks) m.set(hookKey(h), h);
  return [...m.values()];
}

export function emptyRun(header: RunHeader, extra?: Partial<RunView>): RunView {
  return { runId: header.runId, header, hooks: [], cases: {}, history: {}, running: {}, source: "live", nextSeq: 0, done: false, ...extra };
}

export function parseRunFile(text: string, fileName: string): RunView {
  let header: RunHeader | undefined;
  let summary: RunSummary | undefined;
  const hooks: HookResult[] = [];
  const cases: Record<string, CaseResult> = {};
  const history: Record<string, CaseResult[]> = {};
  text.split(/\r?\n/).forEach((line, i) => {
    if (!line.trim()) return;
    let o: Record<string, unknown>;
    try {
      o = JSON.parse(line);
    } catch {
      throw new Error(`Line ${i + 1} of ${fileName} is not JSON`);
    }
    const kind = o.kind;
    delete o.kind;
    if (kind === "header") header = o as unknown as RunHeader;
    else if (kind === "hook") hooks.push(o as unknown as HookResult);
    else if (kind === "case") {
      const c = o as unknown as CaseResult;
      const prev = cases[c.caseId];
      if (prev) history[c.caseId] = [...(history[c.caseId] ?? []), prev];
      cases[c.caseId] = c;
    } else if (kind === "summary") summary = o as unknown as RunSummary;
  });
  if (!header) throw new Error(`${fileName} is not a run file (no header line)`);
  return { runId: header.runId, header, hooks, cases, history, running: {}, summary, source: "file", fileName, nextSeq: 0, done: true };
}

/** The run as a result file: earlier attempts come before the latest one of each test case. */
export function serializeRun(run: RunView): string {
  const lines: string[] = [JSON.stringify({ kind: "header", ...run.header })];
  for (const h of run.hooks) lines.push(JSON.stringify({ kind: "hook", ...h }));
  for (const id of caseOrder(run)) {
    for (const old of run.history[id] ?? []) lines.push(JSON.stringify({ kind: "case", ...old }));
    if (run.cases[id]) lines.push(JSON.stringify({ kind: "case", ...run.cases[id] }));
  }
  const summary = summaryOf(run);
  if (summary) lines.push(JSON.stringify({ kind: "summary", ...summary }));
  return lines.join("\n") + "\n";
}

/** Run data for reports: the latest result of each test case and hook. */
export function runData(run: RunView, only?: Set<string>) {
  return {
    header: run.header,
    hooks: latestHooks(run),
    cases: caseOrder(run).filter((id) => run.cases[id] && (!only || only.has(id))).map((id) => run.cases[id]),
    summary: summaryOf(run),
  };
}

/** Test case ids in suite order: the header's order, then any result that is not in it. */
export function caseOrder(run: RunView): string[] {
  const ids = [...(run.header.caseIds ?? [])];
  const seen = new Set(ids);
  for (const id of Object.keys(run.cases)) if (!seen.has(id)) ids.push(id);
  return ids;
}

/** The summary with counts that reflect the latest attempts. */
export function summaryOf(run: RunView): RunSummary | undefined {
  if (!run.summary) return undefined;
  const c: Record<string, number> = { pass: 0, fail: 0, error: 0, blocked: 0, skipped: 0 };
  for (const r of Object.values(run.cases)) c[r.status] = (c[r.status] ?? 0) + 1;
  return { ...run.summary, total: Object.keys(run.cases).length || run.summary.total, counts: c };
}

/** The first step that did not pass, for the failure summary of a test case. */
export function firstFailure(c: CaseResult): StepResult | undefined {
  return c.steps?.find((s) => s.status !== "pass" && s.status !== "skipped");
}

/** A signature that groups failures with the same cause: failing step, assertion shape or error kind. */
export function failureSignature(c: CaseResult): string {
  const s = firstFailure(c);
  if (!s) return c.status === "blocked" || c.status === "skipped" ? `${c.status}: ${shape(c.reason ?? "")}` : c.status;
  const failed = s.assertions?.find((a) => !a.pass);
  const where = `${s.target?.method ?? ""} ${(s.target?.url ?? "").replace(/^https?:\/\/[^/]+/, "").replace(/\?.*$/, "").replace(/\/\d+(?=\/|$)/g, "/{n}")}`;
  if (failed && /^status\b/.test(failed.expr)) {
    const m = /expected (.+), got (\d+)/.exec(failed.message ?? "");
    return `${where} | HTTP ${m ? `${m[2]} (expected ${m[1]})` : shape(failed.message ?? "")}`;
  }
  if (failed) return `${where} | ${failed.expr.replace(/(==|!=|>=|<=|>|<|contains|matches)\s.*$/, "$1 …")} | ${shape(failed.message ?? "")}`;
  if (s.diffs?.length) return `${where} | diff | ${shape(s.diffs[0])}`;
  return `${where} | ${shape(s.reason ?? s.status)}`;
}

function shape(text: string): string {
  return text.replace(/"[^"]*"/g, '"…"').replace(/\d+(\.\d+)?/g, "#").slice(0, 160);
}

export function fmtMs(ms?: number): string {
  if (ms === undefined || ms === null) return "";
  if (ms < 1000) return `${ms} ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)} s`;
  const m = Math.floor(ms / 60000);
  return `${m}m ${String(Math.round((ms % 60000) / 1000)).padStart(2, "0")}s`;
}

export function fmtTime(iso?: string): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  return d.toLocaleString(undefined, { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" });
}

export function fmtClock(iso?: string): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  return d.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

export function fmtSize(n?: number): string {
  if (n === undefined || n === null) return "";
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}
