import type { Body, Defaults, Folder, Node, Step, Suite, TestCase } from "./types";

export type Phase = "pre" | "step" | "cleanup" | "setup" | "teardown";

export function newId(prefix: string): string {
  const chars = "0123456789abcdefghijklmnopqrstuvwxyz";
  let s = prefix + "-";
  const buf = new Uint8Array(8);
  crypto.getRandomValues(buf);
  for (const b of buf) s += chars[b % chars.length];
  return s;
}

export interface Located {
  node: Node;
  parent: Folder | null;
  index: number;
  path: Folder[];
}

export function walk(items: Node[] | undefined, fn: (n: Node, path: Folder[]) => void, path: Folder[] = []) {
  for (const n of items ?? []) {
    fn(n, path);
    if (n.type === "folder") walk(n.items, fn, [...path, n]);
  }
}

export function find(suite: Suite, id: string | undefined): Located | null {
  if (!id) return null;
  let hit: Located | null = null;
  const visit = (items: Node[] | undefined, parent: Folder | null, path: Folder[]) => {
    (items ?? []).forEach((n, index) => {
      if (hit) return;
      if (n.id === id) hit = { node: n, parent, index, path };
      else if (n.type === "folder") visit(n.items, n, [...path, n]);
    });
  };
  visit(suite.items, null, []);
  return hit;
}

export function allCases(suite: Suite): { tc: TestCase; path: Folder[] }[] {
  const out: { tc: TestCase; path: Folder[] }[] = [];
  walk(suite.items, (n, path) => {
    if (n.type === "case") out.push({ tc: n, path });
  });
  return out;
}

export function allFolders(suite: Suite): { folder: Folder; path: Folder[] }[] {
  const out: { folder: Folder; path: Folder[] }[] = [];
  walk(suite.items, (n, path) => {
    if (n.type === "folder") out.push({ folder: n, path });
  });
  return out;
}

function mapItems(items: Node[] | undefined, fn: (n: Node) => Node | null): Node[] {
  const out: Node[] = [];
  for (const n of items ?? []) {
    const m = fn(n);
    if (m === null) continue;
    if (m.type === "folder") out.push({ ...m, items: mapItems(m.items, fn) });
    else out.push(m);
  }
  return out;
}

export function updateNode(suite: Suite, id: string, fn: (n: Node) => Node): Suite {
  return { ...suite, items: mapItems(suite.items, (n) => (n.id === id ? fn(n) : n)) };
}

export function removeNodes(suite: Suite, ids: Set<string>): Suite {
  return { ...suite, items: mapItems(suite.items, (n) => (ids.has(n.id) ? null : n)) };
}

export function isInside(suite: Suite, folderId: string, candidateId: string | null): boolean {
  if (!candidateId) return false;
  const loc = find(suite, candidateId);
  if (!loc) return false;
  return candidateId === folderId || loc.path.some((f) => f.id === folderId);
}

/** Inserts nodes into a folder (null = suite root) at an index (-1 = end). */
export function insertNodes(suite: Suite, parentId: string | null, index: number, nodes: Node[]): Suite {
  const put = (items: Node[] | undefined) => {
    const list = [...(items ?? [])];
    const at = index < 0 || index > list.length ? list.length : index;
    list.splice(at, 0, ...nodes);
    return list;
  };
  if (parentId === null) return { ...suite, items: put(suite.items) };
  return updateNode(suite, parentId, (n) => (n.type === "folder" ? { ...n, items: put(n.items) } : n));
}

export function moveNodes(suite: Suite, ids: string[], parentId: string | null, index: number): Suite {
  const moving: Node[] = [];
  for (const id of ids) {
    const loc = find(suite, id);
    if (!loc) continue;
    if (parentId && loc.node.type === "folder" && isInside(suite, loc.node.id, parentId)) continue;
    moving.push(loc.node);
  }
  if (!moving.length) return suite;
  let target = index;
  if (target >= 0) {
    const parentItems = parentId === null ? suite.items ?? [] : ((find(suite, parentId)?.node as Folder)?.items ?? []);
    const before = parentItems.slice(0, target).filter((n) => moving.some((m) => m.id === n.id)).length;
    target -= before;
  }
  const without = removeNodes(suite, new Set(moving.map((m) => m.id)));
  return insertNodes(without, parentId, target, moving);
}

function keysOf(items: Node[] | undefined): Set<string> {
  return new Set((items ?? []).filter((n): n is TestCase => n.type === "case").map((c) => c.key.toLowerCase()));
}

export function uniqueKey(siblings: Node[] | undefined, base: string): string {
  const taken = keysOf(siblings);
  if (!taken.has(base.toLowerCase())) return base;
  for (let i = 2; ; i++) {
    const k = `${base}-${i}`;
    if (!taken.has(k.toLowerCase())) return k;
  }
}

export function cloneNode(n: Node, siblings: Node[] | undefined, rename = true): Node {
  if (n.type === "case") {
    return {
      ...structuredClone(n),
      id: newId("c"),
      key: rename ? uniqueKey(siblings, n.key ? `${n.key}-COPY` : "COPY") : n.key,
      name: rename && n.name ? `${n.name} (copy)` : n.name,
    };
  }
  const copy: Folder = { ...structuredClone(n), id: newId("f"), name: rename ? `${n.name} (copy)` : n.name };
  copy.items = (n.items ?? []).map((c) => cloneNode(c, undefined, false));
  return copy;
}

export function stepsOf(node: Node | Suite, phase: Phase): Step[] {
  const n = node as unknown as Record<string, unknown>;
  const field = phase === "pre" ? "preconditions" : phase === "step" ? "steps" : phase;
  return (n[field] as Step[] | undefined) ?? [];
}

export function withSteps<T extends Node | Suite>(node: T, phase: Phase, steps: Step[]): T {
  const field = phase === "pre" ? "preconditions" : phase === "step" ? "steps" : phase;
  return { ...node, [field]: steps };
}

export function emptyStep(): Step {
  return { name: "New step", method: "GET", url: "/" };
}

/** The next key in the style of the folder's keys: after REG-R016 and REG-R017 comes REG-R018. */
export function nextKey(siblings: Node[] | undefined): string {
  const keys = (siblings ?? []).filter((n): n is TestCase => n.type === "case").map((c) => c.key).filter(Boolean);
  let prefix: string | null = null;
  let width = 1;
  for (const k of keys) {
    const m = /^(.*?)(\d+)$/.exec(k);
    if (m) {
      prefix = m[1];
      width = m[2].length;
    }
  }
  if (prefix === null) return uniqueKey(siblings, `TC-${keys.length + 1}`);
  let max = 0;
  for (const k of keys) {
    if (!k.startsWith(prefix)) continue;
    const rest = k.slice(prefix.length);
    if (/^\d+$/.test(rest)) max = Math.max(max, Number(rest));
  }
  return uniqueKey(siblings, prefix + String(max + 1).padStart(width, "0"));
}

export function emptyCase(siblings: Node[] | undefined): TestCase {
  return { type: "case", id: newId("c"), key: nextKey(siblings), name: "New test case", steps: [emptyStep()] };
}

export function emptyFolder(): Folder {
  return { type: "folder", id: newId("f"), name: "New folder", items: [] };
}

export function caseLabel(tc: { key?: string; name?: string }): string {
  const key = tc.key ?? "";
  const name = tc.name ?? "";
  if (!key) return name;
  if (!name || name === key) return key;
  return `${key} ${name}`;
}

export interface ReplaceOptions {
  find: string;
  replace: string;
  regex: boolean;
  caseSensitive: boolean;
  fields: { url: boolean; headers: boolean; body: boolean; assertions: boolean; extract: boolean; names: boolean };
  scope: Set<string> | null;
}

/** Find and replace text inside steps; returns the new suite and the number of replacements. */
export function replaceInSuite(suite: Suite, o: ReplaceOptions): { suite: Suite; count: number } {
  let count = 0;
  let pattern: RegExp;
  try {
    pattern = o.regex ? new RegExp(o.find, o.caseSensitive ? "g" : "gi")
      : new RegExp(o.find.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), o.caseSensitive ? "g" : "gi");
  } catch {
    return { suite, count: 0 };
  }
  const rep = (s: string | undefined): string | undefined => {
    if (s === undefined || s === null || !o.find) return s;
    const matches = s.match(pattern);
    if (!matches) return s;
    count += matches.length;
    return s.replace(pattern, o.replace);
  };
  const step = (st: Step): Step => {
    const out: Step = { ...st };
    if (o.fields.names) out.name = rep(out.name) ?? out.name;
    if (o.fields.url) {
      out.url = rep(out.url) ?? out.url;
      out.query = out.query?.map((q) => ({ ...q, value: rep(q.value) ?? q.value }));
    }
    if (o.fields.headers) out.headers = out.headers?.map((h) => ({ ...h, value: rep(h.value) ?? h.value }));
    if (o.fields.body && out.body) {
      const b = { ...out.body };
      if (b.json !== undefined) {
        const text = JSON.stringify(b.json, null, 2);
        const replaced = rep(text) ?? text;
        if (replaced !== text) {
          try {
            b.json = JSON.parse(replaced);
          } catch {
            b.json = undefined;
            b.raw = replaced;
          }
        }
      }
      if (b.raw !== undefined) b.raw = rep(b.raw);
      if (b.form) b.form = b.form.map((f) => ({ ...f, value: rep(f.value) ?? f.value }));
      out.body = b;
    }
    if (o.fields.assertions && out.expect) {
      out.expect = { ...out.expect, assertions: out.expect.assertions?.map((a) => rep(a) ?? a), status: rep(out.expect.status) };
    }
    if (o.fields.extract && out.extract) out.extract = out.extract.map((e) => ({ ...e, path: rep(e.path) ?? e.path }));
    return out;
  };
  const inScope = (id: string, path: Folder[]) => !o.scope || o.scope.has(id) || path.some((f) => o.scope!.has(f.id));
  const mapNode = (items: Node[] | undefined, path: Folder[]): Node[] =>
    (items ?? []).map((n) => {
      if (n.type === "folder") {
        const f: Folder = { ...n, items: mapNode(n.items, [...path, n]) };
        if (inScope(n.id, path)) {
          f.setup = n.setup?.map(step);
          f.teardown = n.teardown?.map(step);
        }
        return f;
      }
      if (!inScope(n.id, path)) return n;
      return { ...n, preconditions: n.preconditions?.map(step), steps: n.steps?.map(step), cleanup: n.cleanup?.map(step) };
    });
  const out: Suite = { ...suite, items: mapNode(suite.items, []) };
  if (!o.scope) {
    out.setup = suite.setup?.map(step);
    out.teardown = suite.teardown?.map(step);
  }
  return { suite: out, count };
}

// ---------------------------------------------------------------- defaults, variables and bodies

export interface InheritedDefaults {
  label: string;
  defaults: Defaults;
}

/** Suite and folder defaults that apply to a step, outermost first (later entries win). */
export function defaultsChain(suite: Suite, folders: Folder[]): InheritedDefaults[] {
  const out: InheritedDefaults[] = [];
  if (suite.defaults) out.push({ label: "Suite defaults", defaults: suite.defaults });
  for (const f of folders) if (f.defaults) out.push({ label: `Folder “${f.name}”`, defaults: f.defaults });
  return out;
}

/** Folder chain of a node (folders above a test case; the folder itself included for folder hooks). */
export function folderChain(suite: Suite, nodeId: string | undefined, includeSelf: boolean): Folder[] {
  if (!nodeId) return [];
  const loc = find(suite, nodeId);
  if (!loc) return [];
  return includeSelf && loc.node.type === "folder" ? [...loc.path, loc.node] : loc.path;
}

const VAR = /\{\{\s*([^{}\s]+?)\s*}}/g;

export function namesIn(text: string | undefined, into: Set<string>) {
  if (!text) return;
  for (const m of text.matchAll(VAR)) into.add(m[1]);
}

/** Variables a step reads, built-ins ($...) left out. */
export function namesInStep(step: Step): string[] {
  const s = new Set<string>();
  namesIn(step.url, s);
  step.query?.forEach((q) => { namesIn(q.key, s); namesIn(q.value, s); });
  step.headers?.forEach((h) => { namesIn(h.key, s); namesIn(h.value, s); });
  if (step.body) {
    if (step.body.json !== undefined) namesIn(JSON.stringify(step.body.json), s);
    namesIn(step.body.raw, s);
    step.body.form?.forEach((f) => { namesIn(f.key, s); namesIn(f.value, s); });
  }
  return [...s].filter((n) => !n.startsWith("$"));
}

/** Variables a step saves. */
export function namesSaved(steps: Step[] | undefined): string[] {
  return (steps ?? []).flatMap((st) => (st.extract ?? []).map((e) => e.name)).filter(Boolean);
}

export function bodyToText(b?: Body): string {
  if (!b) return "";
  if (b.type === "form") return "";
  if (b.json !== undefined && b.json !== null) return JSON.stringify(b.json, null, 2);
  return b.raw ?? "";
}

/** JSON text becomes a parsed document when it is valid JSON, otherwise it is kept as text (e.g. {{n}} placeholders). */
export function jsonTextToBody(text: string): Body | undefined {
  if (!text.trim()) return undefined;
  try {
    const v = JSON.parse(text);
    if (v !== null && typeof v === "object") return { type: "json", json: v };
  } catch {
    /* not JSON on its own */
  }
  return { type: "json", raw: text };
}

export function stepKey(nodeId: string | undefined, phase: Phase, index: number): string {
  return `${nodeId ?? "suite"}:${phase}:${index}`;
}

export const PHASE_LABEL: Record<Phase, string> = {
  pre: "Preconditions",
  step: "Steps",
  cleanup: "Cleanup",
  setup: "Setup",
  teardown: "Teardown",
};
