// Earlier runs of the same suite, reduced to the outcome of each test case: for "compared with" and the recent-runs
// dots. They come from the server's results folder, the results folder picked in the browser, and this session.
import { api } from "../api";
import type { RunView } from "../runs";
import { useStore } from "../store";
import type { HistoryStatuses } from "../api";

export interface PastRun {
  runId: string;
  name?: string;
  suite: string;
  env: string;
  baselineEnv?: string;
  startedAt: string;
  statuses: Record<string, string>;
}

const fileCache = new Map<string, PastRun | null>();
let loading: Promise<void> | null = null;

/** Loads the past runs of a suite into the store (once per suite unless forced). */
export function loadPast(suite: string, force = false): Promise<void> {
  const st = useStore.getState();
  if (!force && st.pastSuite === suite && st.pastRuns) return Promise.resolve();
  if (loading && !force) return loading;
  loading = (async () => {
    let list: PastRun[] = [];
    try {
      if (st.serverFile) list = (await api.statuses(suite, 15)).map(fromServer);
      else if (st.resultsDir) list = await fromFolder(st.resultsDir, suite);
    } catch {
      list = [];
    }
    useStore.setState({ pastRuns: list, pastSuite: suite });
  })().finally(() => {
    loading = null;
  });
  return loading;
}

function fromServer(h: HistoryStatuses): PastRun {
  return { runId: h.runId, name: h.name, suite: h.suite, env: h.env, baselineEnv: h.baselineEnv, startedAt: h.startedAt, statuses: h.statuses ?? {} };
}

/* eslint-disable @typescript-eslint/no-explicit-any */
async function fromFolder(dir: any, suite: string): Promise<PastRun[]> {
  if (dir.queryPermission && (await dir.queryPermission({ mode: "read" })) !== "granted") return [];
  const files: { name: string; file: File }[] = [];
  for await (const [name, h] of dir.entries()) {
    if (h.kind !== "file" || !/\.jsonl$/i.test(name)) continue;
    files.push({ name, file: await h.getFile() });
  }
  files.sort((a, b) => b.file.lastModified - a.file.lastModified);
  const out: PastRun[] = [];
  for (const { name, file } of files) {
    if (out.length >= 15) break;
    const key = `${name}|${file.size}|${file.lastModified}`;
    let run = fileCache.get(key);
    if (run === undefined) {
      run = null;
      try {
        const head = await file.slice(0, 256 * 1024).text();
        const first = JSON.parse(head.split("\n", 1)[0]);
        if (first.kind === "header" && first.suite === suite) run = parseStatuses(await file.text(), name);
      } catch {
        run = null;
      }
      fileCache.set(key, run);
    }
    if (run && run.suite === suite) out.push(run);
  }
  return out;
}

function parseStatuses(text: string, name: string): PastRun | null {
  let run: PastRun | null = null;
  for (const line of text.split("\n")) {
    if (!line.trim()) continue;
    // only the fields needed: avoid building every response body
    if (line.startsWith('{"kind":"header"')) {
      const h = JSON.parse(line);
      run = { runId: h.runId, name, suite: h.suite, env: h.env, baselineEnv: h.baselineEnv, startedAt: h.startedAt, statuses: {} };
    } else if (run && line.startsWith('{"kind":"case"')) {
      const id = /"caseId":"([^"]+)"/.exec(line)?.[1];
      const status = /"status":"([a-z]+)"/.exec(line)?.[1];
      if (id && status) run.statuses[id] = status;
    }
  }
  return run;
}

/** Past runs plus the finished runs of this session, newest first, without duplicates. */
export function allPast(past: PastRun[] | null, runs: RunView[]): PastRun[] {
  const m = new Map<string, PastRun>();
  for (const r of runs) {
    if (!r.done) continue;
    const statuses: Record<string, string> = {};
    for (const [id, c] of Object.entries(r.cases)) statuses[id] = c.status;
    m.set(r.runId, { runId: r.runId, name: r.fileName, suite: r.header.suite, env: r.header.env, baselineEnv: r.header.baselineEnv, startedAt: r.header.startedAt, statuses });
  }
  for (const p of past ?? []) if (!m.has(p.runId)) m.set(p.runId, p);
  return [...m.values()].sort((a, b) => b.startedAt.localeCompare(a.startedAt));
}

/** Runs of the same suite and environments that started before this one, newest first. */
export function earlierRuns(run: RunView, all: PastRun[]): PastRun[] {
  return all.filter((p) => p.runId !== run.runId && p.suite === run.header.suite && p.env === run.header.env
    && (p.baselineEnv ?? "") === (run.header.baselineEnv ?? "") && p.startedAt < run.header.startedAt);
}

/** The run to compare with: the one picked, or else the latest earlier run. */
export function compareTarget(run: RunView, all: PastRun[], pick: string): PastRun | undefined {
  if (pick === "none") return undefined;
  if (pick && pick !== "auto") return all.find((p) => p.runId === pick && p.runId !== run.runId);
  return earlierRuns(run, all)[0];
}

/** Outcome of one test case in the last few runs, oldest first, ending with this run. */
export function recentOutcomes(run: RunView, all: PastRun[], caseId: string, n = 8): { runId: string; startedAt: string; status?: string }[] {
  const older = earlierRuns(run, all).slice(0, n - 1).reverse();
  return [
    ...older.map((p) => ({ runId: p.runId, startedAt: p.startedAt, status: p.statuses[caseId] })),
    { runId: run.runId, startedAt: run.header.startedAt, status: run.cases[caseId]?.status },
  ];
}
