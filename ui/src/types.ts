export interface KeyValue { key: string; value: string; disabled?: boolean }

export interface Body { type: "json" | "form" | "text"; json?: unknown; raw?: string; form?: KeyValue[] }
export interface Extract { name: string; path: string; scope?: "folder" | "suite" }
export interface Expect { status?: string; assertions?: string[]; maxTimeMs?: number }
export interface Retry { times?: number; intervalMs?: number }
export interface StepOptions {
  delayMs?: number; timeoutMs?: number; retry?: Retry; compare?: boolean; ignoreFields?: string[]; required?: boolean;
}
export interface Step {
  name: string; disabled?: boolean; method: string; url: string; query?: KeyValue[]; headers?: KeyValue[];
  body?: Body; auth?: string; extract?: Extract[]; expect?: Expect; options?: StepOptions; notes?: string;
}
export interface Defaults { headers?: KeyValue[]; auth?: string; timeoutMs?: number }
export interface RunPolicy { cases?: "parallel" | "serial"; folders?: "parallel" | "serial" }
export interface Folder {
  type: "folder"; id: string; name: string; description?: string; disabled?: boolean; defaults?: Defaults;
  run?: RunPolicy; setup?: Step[]; teardown?: Step[]; items?: Node[];
}
export interface TestCase {
  type: "case"; id: string; key: string; name: string; description?: string; tags?: string[]; disabled?: boolean;
  dependsOn?: string[]; stopOnFailure?: boolean; preconditions?: Step[]; steps?: Step[]; cleanup?: Step[];
}
export type Node = Folder | TestCase;
export interface Environment { name: string; baseUrl?: string; auth?: string; headers?: KeyValue[]; variables?: KeyValue[] }
export interface AuthProfile {
  name: string; type: string; grantType?: string; tokenUrl?: string; clientId?: string; clientSecret?: string;
  username?: string; password?: string; scope?: string; token?: string; refreshToken?: string;
  extraParams?: KeyValue[]; description?: string;
}
export interface CompareOptions {
  ignoreFields?: string[]; ignoreArrayOrder?: boolean; caseSensitive?: boolean; numericTolerance?: number;
  compareErrorResponses?: boolean;
}
export interface Settings { concurrency?: number; env?: string; baselineEnv?: string; run?: RunPolicy; compare?: CompareOptions }
export interface Suite {
  schema?: string; name: string; description?: string; version?: string; settings?: Settings;
  environments?: Environment[]; authProfiles?: AuthProfile[]; variables?: KeyValue[]; defaults?: Defaults;
  setup?: Step[]; teardown?: Step[]; items?: Node[];
}

export type StatusValue = "pass" | "fail" | "error" | "blocked" | "skipped";
export interface AssertionResult { expr: string; pass: boolean; message?: string }
/** Where a variable's value came from: env, suite, override, builtin, reused, setup, teardown, pre, step, cleanup, unset. */
export interface VarSource { kind: string; owner?: string; step?: number; name?: string }
/** A variable a step used, with its value when the step was sent and the places that used it. */
export interface VarUse { name: string; value?: string | null; from?: VarSource; usedIn?: string[] }
export interface Exchange {
  env?: string; method?: string; url?: string; requestHeaders?: KeyValue[]; requestBody?: string; status?: number;
  timeMs?: number; responseHeaders?: KeyValue[]; contentType?: string; responseSize?: number; responseBody?: string;
  truncated?: boolean; error?: string; vars?: VarUse[];
}
export interface StepRef { phase: string; index: number; name: string }
export interface StepResult {
  phase: string; index: number; name: string; status: StatusValue; reason?: string; attempts?: number; durationMs?: number;
  assertions?: AssertionResult[]; diffs?: string[]; extracted?: Record<string, string>; missed?: string[];
  target?: Exchange; baseline?: Exchange;
}
export interface Triage { label?: string; note?: string }
export interface CaseResult {
  caseId: string; key: string; name: string; folderId?: string; path?: string[]; tags?: string[]; status: StatusValue;
  reason?: string; cleanupFailed?: boolean; startedAt?: string; durationMs?: number; steps?: StepResult[]; triage?: Triage;
  disabledSteps?: StepRef[]; attempt?: number;
}
export interface HookResult {
  folderId?: string; path?: string[]; hook: "setup" | "teardown"; status: StatusValue; reason?: string; startedAt?: string;
  durationMs?: number; steps?: StepResult[]; vars?: Record<string, Record<string, string>>; disabledSteps?: StepRef[]; attempt?: number;
}
/** A test case of the run's scope that did not run because it, or a folder above it (folder), is disabled. */
export interface DisabledCase { caseId: string; key: string; name: string; folderId?: string; path?: string[]; position?: number; folder?: string }
/** Test cases run again inside a run. */
export interface Rerun { runId: string; startedAt: string; finishedAt?: string; caseIds: string[]; hooks?: string }
export interface RunHeader {
  schema?: string; runId: string; suite: string; suiteFile?: string; env: string; baselineEnv?: string; startedAt: string;
  selection?: string; concurrency?: number; capture?: string; hooks?: string; planned?: number; tool?: string; caseIds?: string[];
  disabled?: DisabledCase[]; variables?: Record<string, Record<string, string>>; parent?: string; reruns?: Rerun[];
}
export interface RunSummary {
  total: number; counts: Record<string, number>; finishedAt?: string; durationMs?: number; stopped?: boolean; error?: string;
}
export interface Issue { level: "error" | "warning"; where: string; message: string; nodeId?: string; section?: string; step?: number }
export interface Selection { caseIds?: string[]; caseKeys?: string[]; folderIds?: string[]; folderPaths?: string[]; tags?: string[] }
export interface RunOptions {
  concurrency?: number; capture?: string; bodyLimitKb?: number; hooks?: "run" | "reuse" | "skip";
  reuse?: Record<string, Record<string, Record<string, string>>>; variables?: Record<string, string>; parent?: string;
}
