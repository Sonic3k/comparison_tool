import type { Issue, RunHeader, RunOptions, Selection, Step, StepResult, Suite } from "./types";

async function check(r: Response): Promise<Response> {
  if (r.ok) return r;
  let msg = `${r.status} ${r.statusText}`;
  try {
    const j = await r.json();
    if (j && j.error) msg = j.error;
  } catch {
    /* not json */
  }
  throw new Error(msg);
}

async function json<T>(url: string, body?: unknown): Promise<T> {
  const r = await fetch(url, body === undefined ? undefined : {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  });
  await check(r);
  if (r.status === 204) return undefined as T;
  return r.json();
}

export interface Opened { suite: Suite; format: string; notes: string[]; fileName: string; issues: Issue[] }
/** One result file in the results folder, reduced to the outcome of each test case. */
export interface HistoryStatuses {
  name?: string; runId: string; suite: string; env: string; baselineEnv?: string; startedAt: string; selection?: string;
  counts?: Record<string, number>; statuses: Record<string, string>;
}
export interface Info { tool: string; file?: { name: string; path: string; resultsDir: string } }

export const api = {
  info: () => json<Info>("api/info"),
  startupSuite: async (): Promise<Opened | null> => {
    const r = await fetch("api/startup-suite");
    if (r.status === 204) return null;
    await check(r);
    return r.json();
  },
  saveStartup: (suite: Suite) => json<{ savedAt: string; path: string }>("api/startup-suite", suite),
  open: async (file: File): Promise<Opened> => {
    const fd = new FormData();
    fd.append("file", file, file.name);
    const r = await fetch("api/suite/open", { method: "POST", body: fd });
    await check(r);
    return r.json();
  },
  validate: (suite: Suite) => json<Issue[]>("api/suite/validate", suite),
  exportSuite: async (suite: Suite, format: string, env?: string): Promise<{ blob: Blob; name: string }> => {
    const r = await fetch(`api/suite/export?format=${format}${env ? "&env=" + encodeURIComponent(env) : ""}`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(suite),
    });
    await check(r);
    return { blob: await r.blob(), name: fileName(r) ?? "suite" };
  },
  startRun: (body: { suite: Suite; env?: string; baselineEnv?: string; selection?: Selection; options?: RunOptions; persist?: boolean }) =>
    json<{ runId: string; header: RunHeader; resultFile?: string }>("api/runs", body),
  events: (runId: string, after: number) =>
    json<{ events: { seq: number; type: string; data: unknown }[]; next: number; done: boolean }>(
      `api/runs/${encodeURIComponent(runId)}/events?after=${after}`),
  stop: (runId: string) => json<{ stopping: boolean; hard: boolean }>(`api/runs/${encodeURIComponent(runId)}/stop`, {}),
  saveRun: (runId: string, data: unknown) => json<{ path: string }>(`api/runs/${encodeURIComponent(runId)}/save`, data),
  results: () => json<{ name: string; size: number; modified: string }[]>("api/results"),
  statuses: (suite?: string, limit = 12) =>
    json<HistoryStatuses[]>(`api/results/statuses?limit=${limit}${suite ? "&suite=" + encodeURIComponent(suite) : ""}`),
  saveResult: async (name: string, text: string): Promise<{ path: string; name: string }> => {
    const r = await fetch(`api/results/${encodeURIComponent(name)}`, { method: "POST", headers: { "Content-Type": "text/plain;charset=UTF-8" }, body: text });
    await check(r);
    return r.json();
  },
  result: async (name: string): Promise<string> => {
    const r = await fetch(`api/results/${encodeURIComponent(name)}`);
    await check(r);
    return r.text();
  },
  report: async (data: unknown, format: "excel" | "junit"): Promise<{ blob: Blob; name: string }> => {
    const r = await fetch(`api/report?format=${format}`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(data),
    });
    await check(r);
    return { blob: await r.blob(), name: fileName(r) ?? "report" };
  },
  tryStep: (body: { suite: Suite; env?: string; baselineEnv?: string; folderId?: string; step: Step; vars?: Record<string, string> }) =>
    json<StepResult>("api/try", body),
  curl: (body: { suite: Suite; env?: string; baselineEnv?: string; folderId?: string; step: Step; vars?: Record<string, string> }) =>
    json<{ lane: string; env: string; url: string; curl: string; error?: string }[]>("api/curl/build", body),
  parseCurl: (text: string) => json<Step>("api/curl/parse", { text }),
};

function fileName(r: Response): string | null {
  const cd = r.headers.get("Content-Disposition") ?? "";
  const star = /filename\*=UTF-8''([^;]+)/i.exec(cd);
  if (star) return decodeURIComponent(star[1]);
  const plain = /filename="?([^";]+)"?/i.exec(cd);
  return plain ? plain[1] : null;
}
