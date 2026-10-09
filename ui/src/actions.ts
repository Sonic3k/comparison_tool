// User actions that combine the store, the server API and file access.
import { createElement } from "react";
import { api } from "./api";
import { canSaveFiles, download, idbGet, idbSet, pickFile, pickFolder, saveAs, writeHandle, writeInFolder } from "./files";
import { reveal } from "./nav";
import type { RunView } from "./runs";
import { parseRunFile, runData, serializeRun } from "./runs";
import { runFileName, safe, useStore } from "./store";
import { cloneNode, emptyCase, emptyFolder, find, insertNodes, removeNodes, updateNode } from "./suiteOps";
import type { Folder, Node, Suite } from "./types";
import { RunDialog, reuseMap, selectionOf } from "./components/Dialogs";
import { choose, useOverlay } from "./components/ui";

const SUITE_TYPES = {
  "application/json": [".json"],
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": [".xlsx"],
};

/* eslint-disable @typescript-eslint/no-explicit-any */

// ---------------------------------------------------------------- opening

export function isRunFile(name: string): boolean {
  return /\.(run\.)?jsonl$/i.test(name);
}

export async function openFile(file: File, handle?: unknown): Promise<void> {
  if (isRunFile(file.name)) return openRunFile(file);
  return openSuiteFile(file, handle);
}

export async function openSuiteFile(file: File, handle?: unknown): Promise<void> {
  const st = useStore.getState();
  if (st.serverFile) {
    st.toast("error", "This window edits the suite the tool was started with. Start the tool without a file to open others.");
    return;
  }
  if (!(await confirmDiscard())) return;
  try {
    const res = await api.open(file);
    const v1 = res.format === "v1";
    const fileName = v1 ? file.name.replace(/(\.suite)?\.json$/i, "") + ".suite.json" : file.name;
    useStore.getState().load(res.suite, { fileName, handle: v1 ? null : handle ?? null, format: res.format, notes: res.notes, issues: res.issues });
    if (v1) {
      useStore.getState().toast("info", `Converted from the old format (${res.notes.length} note${res.notes.length === 1 ? "" : "s"}). Save writes a new ${fileName}; the old file is not changed.`);
    } else if (handle) {
      rememberRecent(handle, file.name);
    }
  } catch (e) {
    useStore.getState().toast("error", `Could not open ${file.name}: ${(e as Error).message}`);
  }
}

export async function openRunFile(file: File): Promise<void> {
  try {
    const run = parseRunFile(await file.text(), file.name);
    useStore.getState().addRun(run);
  } catch (e) {
    useStore.getState().toast("error", (e as Error).message);
  }
}

export async function pickAndOpen(): Promise<void> {
  try {
    const picked = await pickFile({ ...SUITE_TYPES, "application/x-ndjson": [".jsonl"] }, "Suites and run results");
    if (picked) await openFile(picked.file, picked.handle);
  } catch (e) {
    useStore.getState().toast("error", (e as Error).message);
  }
}

export async function pickRunFile(): Promise<void> {
  const picked = await pickFile({ "application/x-ndjson": [".jsonl"] }, "Run results");
  if (picked) await openRunFile(picked.file);
}

// ---------------------------------------------------------------- recent files and drafts

interface Recent {
  name: string;
  handle: any;
  at: number;
}

export async function rememberRecent(handle: any, name: string) {
  if (!handle || typeof handle.getFile !== "function") return;
  const list = ((await idbGet<Recent[]>("recent")) ?? []).filter((r) => r.name !== name);
  await idbSet("recent", [{ name, handle, at: Date.now() }, ...list].slice(0, 8));
}

export async function recentFiles(): Promise<Recent[]> {
  return (await idbGet<Recent[]>("recent")) ?? [];
}

export async function openRecent(r: Recent): Promise<void> {
  try {
    if (r.handle.queryPermission && (await r.handle.queryPermission({ mode: "readwrite" })) !== "granted") {
      if ((await r.handle.requestPermission({ mode: "readwrite" })) !== "granted") return;
    }
    await openFile(await r.handle.getFile(), r.handle);
  } catch (e) {
    useStore.getState().toast("error", `Could not open ${r.name}: ${(e as Error).message}`);
    const list = (await recentFiles()).filter((x) => x.name !== r.name);
    await idbSet("recent", list);
  }
}

export interface Draft {
  suite: Suite;
  fileName: string | null;
  format?: string;
  handle?: any;
  at: number;
}

export async function loadDraft(): Promise<Draft | null> {
  const d = await idbGet<Draft | null>("draft");
  return d && d.suite ? d : null;
}

export function restoreDraft(d: Draft) {
  const keepHandle = d.format === "v2" || d.format === "excel";
  useStore.getState().load(d.suite, { fileName: d.fileName, handle: keepHandle ? d.handle ?? null : null, format: d.format ?? "v2" });
  useStore.setState({ dirty: true });
}

// ---------------------------------------------------------------- saving

/** Asks before unsaved changes are thrown away; true = go on. */
export async function confirmDiscard(): Promise<boolean> {
  const st = useStore.getState();
  if (!st.suite || !st.dirty) return true;
  const answer = await choose("Unsaved changes", `Save the changes to ${st.fileName ?? st.suite.name} first?`, [
    { id: "cancel", label: "Cancel" },
    { id: "discard", label: "Don't save", variant: "danger" },
    { id: "save", label: "Save", variant: "primary" },
  ]);
  if (answer === "save") return saveSuite();
  return answer === "discard";
}

async function suiteBlob(suite: Suite, format: "json" | "excel"): Promise<Blob> {
  return (await api.exportSuite(suite, format)).blob;
}

export async function saveSuite(): Promise<boolean> {
  const st = useStore.getState();
  const suite = st.suite;
  if (!suite) return false;
  try {
    if (st.serverFile) {
      const r = await api.saveStartup(suite);
      useStore.getState().markSaved(undefined, undefined, st.format ?? "v2");
      useStore.getState().toast("ok", `Saved to ${r.path}`);
      return true;
    }
    if (st.handle && (st.format === "v2" || st.format === "excel")) {
      await writeHandle(st.handle, await suiteBlob(suite, st.format === "excel" ? "excel" : "json"));
      useStore.getState().markSaved(undefined, undefined, st.format);
      return true;
    }
    return await saveSuiteAs();
  } catch (e) {
    useStore.getState().toast("error", "Save failed: " + (e as Error).message);
    return false;
  }
}

export async function saveSuiteAs(): Promise<boolean> {
  const st = useStore.getState();
  const suite = st.suite;
  if (!suite) return false;
  const base = (st.fileName ?? safe(suite.name)).replace(/\.(suite\.json|json|xlsx)$/i, "");
  const name = `${base}.suite.json`;
  try {
    const blob = await suiteBlob(suite, "json");
    if (canSaveFiles) {
      const handle = await saveAs(blob, name, "Comparison Tool suite", { "application/json": [".json"] });
      if (handle === undefined) return false;
      if (st.serverFile) {
        useStore.getState().toast("ok", `Copy saved as ${handle.name}`);
        return true;
      }
      useStore.getState().markSaved(handle, handle.name, "v2");
      rememberRecent(handle, handle.name);
      return true;
    }
    download(blob, name);
    if (!st.serverFile) useStore.getState().markSaved(null, name, "v2");
    useStore.getState().toast("ok", `Downloaded ${name}. Open it from your downloads folder next time.`);
    return true;
  } catch (e) {
    useStore.getState().toast("error", "Save failed: " + (e as Error).message);
    return false;
  }
}

export async function exportSuite(format: "excel" | "postman" | "bruno" | "json"): Promise<void> {
  const st = useStore.getState();
  if (!st.suite) return;
  try {
    const { blob, name } = await api.exportSuite(st.suite, format, st.env);
    const types: Record<string, Record<string, string[]>> = {
      excel: { "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": [".xlsx"] },
      postman: { "application/json": [".json"] },
      bruno: { "application/zip": [".zip"] },
      json: { "application/json": [".json"] },
    };
    if (canSaveFiles) {
      const h = await saveAs(blob, name, "Export", types[format]);
      if (h) st.toast("ok", `Exported ${h.name}`);
    } else {
      download(blob, name);
    }
  } catch (e) {
    st.toast("error", "Export failed: " + (e as Error).message);
  }
}

export async function newSuite(): Promise<void> {
  if (useStore.getState().serverFile) return;
  if (!(await confirmDiscard())) return;
  const first = emptyCase([]);
  const suite: Suite = {
    schema: "comparison-tool/suite@2",
    name: "New suite",
    settings: { concurrency: 4 },
    environments: [{ name: "local", baseUrl: "http://localhost:8080" }],
    items: [{ ...emptyFolder(), name: "First folder", items: [{ ...first, key: "TC-1", name: "First test case" }] }],
  };
  useStore.getState().load(suite, { fileName: null, format: "v2" });
  useStore.setState({ dirty: true });
}

// ---------------------------------------------------------------- tree items

function parentOf(suite: Suite, id: string | undefined): { parentId: string | null; index: number } {
  if (!id) return { parentId: null, index: -1 };
  const loc = find(suite, id);
  if (!loc) return { parentId: null, index: -1 };
  if (loc.node.type === "folder") return { parentId: loc.node.id, index: -1 };
  return { parentId: loc.parent?.id ?? null, index: loc.index + 1 };
}

/** Adds a test case next to the selection (inside a selected folder). */
export function addCase(where?: { parentId: string | null; index: number }) {
  const st = useStore.getState();
  if (!st.suite) return;
  const at = where ?? parentOf(st.suite, st.sel.kind === "suite" ? undefined : st.sel.id);
  const siblings = at.parentId ? (find(st.suite, at.parentId)?.node as Folder | undefined)?.items : st.suite.items;
  const tc = emptyCase(siblings);
  st.edit((s) => insertNodes(s, at.parentId, at.index, [tc]));
  if (at.parentId) {
    reveal(at.parentId);
    st.toggleExpanded(at.parentId, true);
  }
  st.select({ kind: "case", id: tc.id, phase: "step", step: 0 });
}

export function addFolder(where?: { parentId: string | null; index: number }) {
  const st = useStore.getState();
  if (!st.suite) return;
  const at = where ?? parentOf(st.suite, st.sel.kind === "suite" ? undefined : st.sel.id);
  const f = emptyFolder();
  st.edit((s) => insertNodes(s, at.parentId, at.index, [f]));
  if (at.parentId) {
    reveal(at.parentId);
    st.toggleExpanded(at.parentId, true);
  }
  st.toggleExpanded(f.id, true);
  st.select({ kind: "folder", id: f.id, tab: "settings" });
}

/** Ids without the ones inside another selected folder. */
function topLevel(suite: Suite, ids: string[]): string[] {
  const set = new Set(ids);
  return ids.filter((id) => {
    const loc = find(suite, id);
    return loc && !loc.path.some((f) => set.has(f.id));
  });
}

export function duplicateNodes(ids: string[]) {
  const st = useStore.getState();
  if (!st.suite || !ids.length) return;
  const roots = topLevel(st.suite, ids);
  const created: Node[] = [];
  st.edit((s) => {
    let out = s;
    for (const id of roots) {
      const loc = find(out, id);
      if (!loc) continue;
      const siblings = loc.parent ? loc.parent.items : out.items;
      const copy = cloneNode(loc.node, siblings);
      created.push(copy);
      out = insertNodes(out, loc.parent?.id ?? null, loc.index + 1, [copy]);
    }
    return out;
  });
  if (created.length === 1) {
    const n = created[0];
    if (n.type === "case") st.select({ kind: "case", id: n.id, phase: "step", step: 0 });
    else st.select({ kind: "folder", id: n.id, tab: "settings" });
  } else {
    st.setMulti(created.map((n) => n.id));
  }
}

export function deleteNodes(ids: string[]) {
  const st = useStore.getState();
  if (!st.suite || !ids.length) return;
  const roots = topLevel(st.suite, ids);
  let cases = 0;
  for (const id of roots) {
    const n = find(st.suite, id)?.node;
    if (!n) continue;
    if (n.type === "case") cases++;
    else cases += countCases(n);
  }
  const selGone = st.sel.id && (roots.includes(st.sel.id) || roots.some((r) => find(st.suite!, st.sel.id)?.path.some((f) => f.id === r)));
  st.edit((s) => removeNodes(s, new Set(roots)));
  if (selGone) st.select({ kind: "suite", tab: "general" });
  else st.setMulti([]);
  st.toast("info", `Deleted ${roots.length === 1 ? "1 item" : `${roots.length} items`}${cases ? ` (${cases} test case${cases === 1 ? "" : "s"})` : ""}`, {
    label: "Undo",
    run: () => useStore.getState().undo(),
  });
}

function countCases(f: Folder): number {
  let n = 0;
  for (const c of f.items ?? []) n += c.type === "case" ? 1 : countCases(c);
  return n;
}

export function toggleDisabled(ids: string[]) {
  const st = useStore.getState();
  if (!st.suite || !ids.length) return;
  const nodes = ids.map((id) => find(st.suite!, id)?.node).filter(Boolean) as Node[];
  const disable = nodes.some((n) => !n.disabled);
  st.edit((s) => {
    let out = s;
    for (const n of nodes) out = updateNode(out, n.id, (x) => ({ ...x, disabled: disable ? true : undefined }));
    return out;
  });
}

// ---------------------------------------------------------------- running

export function runNodes(ids: string[]) {
  const st = useStore.getState();
  if (!ids.length) return st.startRun(undefined);
  return st.startRun(selectionOf(ids));
}

export function openRunDialog(scope?: "all" | "selected" | "tags") {
  let id = 0;
  id = useOverlay.getState().showDialog(createElement(RunDialog, { onClose: () => useOverlay.getState().closeDialog(id), initialScope: scope }));
}

export function rerun(run: RunView, which: "all" | "notpass", reuseSetup: boolean) {
  const st = useStore.getState();
  const ids = which === "all" ? run.header.caseIds ?? Object.keys(run.cases)
    : Object.values(run.cases).filter((c) => c.status !== "pass").map((c) => c.caseId);
  if (!ids.length) return st.toast("info", "Nothing to run again");
  const options = reuseSetup ? { hooks: "reuse" as const, reuse: reuseMap(run.hooks) } : undefined;
  const envs = (st.suite?.environments ?? []).map((e) => e.name);
  if (run.header.env && run.header.env !== st.env && envs.includes(run.header.env)) {
    st.setEnv(run.header.env, run.header.baselineEnv && envs.includes(run.header.baselineEnv) ? run.header.baselineEnv : undefined);
  }
  st.startRun({ caseIds: ids }, options);
}

export function rerunCase(run: RunView | undefined, caseId: string, reuseSetup = false) {
  const st = useStore.getState();
  if (!st.suite || !find(st.suite, caseId)) return st.toast("error", "This test case is not in the open suite");
  const options = reuseSetup && run ? { hooks: "reuse" as const, reuse: reuseMap(run.hooks) } : undefined;
  st.startRun({ caseIds: [caseId] }, options);
}

// ---------------------------------------------------------------- results

export async function saveRunResult(run: RunView): Promise<void> {
  const st = useStore.getState();
  const text = serializeRun(run);
  try {
    if (run.resultFile && run.source === "live") {
      const r = await api.saveRun(run.runId, runData(run));
      useStore.getState().markRunSaved(run.runId, r.path);
      st.toast("ok", `Saved to ${r.path}`);
      return;
    }
    const name = run.fileName ?? runFileName(run);
    if (st.resultsDir) {
      try {
        await writeInFolder(st.resultsDir, name, text);
        useStore.getState().markRunSaved(run.runId, name);
        st.toast("ok", `Saved ${name} in ${st.resultsDirName ?? "the results folder"}`);
        return;
      } catch {
        /* fall back to a save dialog */
      }
    }
    const blob = new Blob([text], { type: "application/x-ndjson" });
    if (canSaveFiles) {
      const h = await saveAs(blob, name, "Run result", { "application/x-ndjson": [".jsonl"] });
      if (!h) return;
      useStore.getState().markRunSaved(run.runId, h.name);
      st.toast("ok", `Saved ${h.name}`);
    } else {
      download(blob, name);
      useStore.getState().markRunSaved(run.runId, name);
    }
  } catch (e) {
    st.toast("error", "Could not save the result: " + (e as Error).message);
  }
}

export async function exportReport(run: RunView, format: "excel" | "junit"): Promise<void> {
  const st = useStore.getState();
  try {
    const { blob, name } = await api.report(runData(run), format);
    if (canSaveFiles) {
      const h = await saveAs(blob, name, "Report", format === "excel"
        ? { "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": [".xlsx"] }
        : { "application/xml": [".xml"] });
      if (h) st.toast("ok", `Saved ${h.name}`);
    } else {
      download(blob, name);
    }
  } catch (e) {
    st.toast("error", "Report failed: " + (e as Error).message);
  }
}

export async function chooseResultsDir(): Promise<void> {
  try {
    const dir = await pickFolder();
    if (!dir) return;
    useStore.getState().setResultsDir(dir, dir.name);
    useStore.getState().toast("ok", `Finished runs are saved in “${dir.name}”`);
  } catch (e) {
    useStore.getState().toast("error", (e as Error).message);
  }
}

export async function restoreResultsDir(): Promise<void> {
  const dir = await idbGet<any>("resultsDir");
  if (dir && dir.name) useStore.setState({ resultsDir: dir, resultsDirName: dir.name });
}

// ---------------------------------------------------------------- run history (results folder)

export interface HistoryEntry {
  name: string;
  size?: number;
  modified?: string;
  load: () => Promise<string>;
}

/** Saved results: results/ next to the suite in server-file mode, otherwise the chosen results folder. */
export async function historyEntries(): Promise<HistoryEntry[]> {
  const st = useStore.getState();
  if (st.serverFile) {
    const list = await api.results();
    return list.map((r) => ({ ...r, load: () => api.result(r.name) }));
  }
  const dir = st.resultsDir as any;
  if (!dir) return [];
  if (dir.queryPermission && (await dir.queryPermission({ mode: "read" })) !== "granted") {
    if ((await dir.requestPermission({ mode: "read" })) !== "granted") return [];
  }
  const out: HistoryEntry[] = [];
  for await (const [name, h] of dir.entries()) {
    if (h.kind !== "file" || !/\.jsonl$/i.test(name)) continue;
    const f: File = await h.getFile();
    out.push({ name, size: f.size, modified: new Date(f.lastModified).toISOString(), load: () => f.text() });
  }
  return out.sort((a, b) => (b.modified ?? "").localeCompare(a.modified ?? ""));
}

export async function openHistory(e: HistoryEntry): Promise<void> {
  try {
    const run = parseRunFile(await e.load(), e.name);
    const existing = useStore.getState().runs.find((r) => r.runId === run.runId);
    if (existing) useStore.getState().setActiveRun(existing.runId);
    else useStore.getState().addRun(run);
  } catch (err) {
    useStore.getState().toast("error", (err as Error).message);
  }
}
