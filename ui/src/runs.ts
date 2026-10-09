import type { CaseResult, HookResult, RunHeader, RunSummary, StatusValue, StepResult } from "./types";

export interface RunView {
  runId: string;
  header: RunHeader;
  hooks: HookResult[];
  cases: Record<string, CaseResult>;
  running: Record<string, true>;
  summary?: RunSummary;
  source: "live" | "file";
  fileName?: string;
  resultFile?: string;
  nextSeq: number;
  done: boolean;
  triageChanged?: boolean;
  savedTo?: string;
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

export function parseRunFile(text: string, fileName: string): RunView {
  let header: RunHeader | undefined;
  let summary: RunSummary | undefined;
  const hooks: HookResult[] = [];
  const cases: Record<string, CaseResult> = {};
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
    else if (kind === "case") cases[(o as unknown as CaseResult).caseId] = o as unknown as CaseResult;
    else if (kind === "summary") summary = o as unknown as RunSummary;
  });
  if (!header) throw new Error(`${fileName} is not a run file (no header line)`);
  return { runId: header.runId, header, hooks, cases, running: {}, summary, source: "file", fileName, nextSeq: 0, done: true };
}

export function serializeRun(run: RunView): string {
  const lines: string[] = [JSON.stringify({ kind: "header", ...run.header })];
  for (const h of run.hooks) lines.push(JSON.stringify({ kind: "hook", ...h }));
  const order = run.header.caseIds ?? Object.keys(run.cases);
  for (const id of order) if (run.cases[id]) lines.push(JSON.stringify({ kind: "case", ...run.cases[id] }));
  if (run.summary) lines.push(JSON.stringify({ kind: "summary", ...run.summary }));
  return lines.join("\n") + "\n";
}

export function runData(run: RunView) {
  const order = run.header.caseIds ?? Object.keys(run.cases);
  return {
    header: run.header,
    hooks: run.hooks,
    cases: order.filter((id) => run.cases[id]).map((id) => run.cases[id]),
    summary: run.summary,
  };
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

export interface Change {
  caseId: string;
  kind: "fixed" | "still" | "new" | "same";
}

/** How each test case moved between two runs. */
export function compareRuns(older: RunView, newer: RunView): Record<string, Change["kind"]> {
  const out: Record<string, Change["kind"]> = {};
  for (const [id, n] of Object.entries(newer.cases)) {
    const o = older.cases[id];
    if (!o) continue;
    const wasOk = o.status === "pass";
    const isOk = n.status === "pass";
    out[id] = wasOk && isOk ? "same" : !wasOk && isOk ? "fixed" : wasOk && !isOk ? "new" : "still";
  }
  return out;
}

export function fmtMs(ms?: number): string {
  if (ms === undefined || ms === null) return "";
  if (ms < 1000) return `${ms} ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)} s`;
  const m = Math.floor(ms / 60000);
  return `${m}m ${Math.round((ms % 60000) / 1000)}s`;
}

export function fmtTime(iso?: string): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  return d.toLocaleString(undefined, { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" });
}

export function fmtSize(n?: number): string {
  if (n === undefined || n === null) return "";
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}
