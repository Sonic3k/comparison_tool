import { useEffect, useMemo, useRef, useState } from "react";
import type { DragEvent, KeyboardEvent, MouseEvent } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import {
  ChevronDown, ChevronRight, ChevronsDownUp, Copy, CopyPlus, FilePlus, FileText, Folder as FolderIcon, FolderOpen, FolderPlus,
  Layers, Play, Power, Search, SlidersHorizontal, Trash2, X,
} from "lucide-react";
import { addCase, addFolder, deleteNodes, duplicateNodes, openRunDialog, runNodes, toggleDisabled } from "../actions";
import { lastStatuses, useStore } from "../store";
import { allFolders, find, moveNodes } from "../suiteOps";
import type { Folder, Node, Suite, TestCase } from "../types";
import { Button, copyText, isTyping, openContextMenu } from "./ui";
import type { MenuItem } from "./ui";

interface Row {
  kind: "suite" | "hook" | "folder" | "case";
  id: string;
  depth: number;
  node?: Node;
  parentId: string | null;
  index: number;
  hook?: "setup" | "teardown";
  count?: number;
  status?: string;
}

const RANK: Record<string, number> = { running: 6, fail: 5, error: 4, blocked: 3, skipped: 1, pass: 2 };
let savedScroll = 0;

export function Tree() {
  const suite = useStore((s) => s.suite)!;
  const expanded = useStore((s) => s.expanded);
  const sel = useStore((s) => s.sel);
  const multi = useStore((s) => s.multi);
  const runs = useStore((s) => s.runs);
  const query = useStore((s) => s.treeSearch);
  const statuses = useMemo(() => lastStatuses(runs), [runs]);
  const rows = useMemo(() => flatten(suite, expanded, query, statuses), [suite, expanded, query, statuses]);
  const listRef = useRef<HTMLDivElement>(null);
  const anchor = useRef<string | null>(null);
  const [drop, setDrop] = useState<{ id: string; pos: "before" | "after" | "inside" } | null>(null);
  const searchRef = useRef<HTMLInputElement>(null);

  const v = useVirtualizer({
    count: rows.length,
    getScrollElement: () => listRef.current,
    estimateSize: () => 28,
    overscan: 16,
    initialOffset: savedScroll,
  });

  const selectedId = sel.kind === "suite" ? (sel.tab === "hooks" && sel.phase ? `suite:${sel.phase}` : "suite") : sel.id;
  useEffect(() => {
    const i = rows.findIndex((r) => r.id === selectedId);
    if (i >= 0) v.scrollToIndex(i, { align: "auto" });
    // only when the selection changes
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId]);

  useEffect(() => {
    const onFocusSearch = () => searchRef.current?.focus();
    window.addEventListener("ct:search", onFocusSearch);
    return () => window.removeEventListener("ct:search", onFocusSearch);
  }, []);

  const caseCount = useMemo(() => rows.filter((r) => r.kind === "case").length, [rows]);
  const totalCases = useMemo(() => countAll(suite.items), [suite]);

  const open = (r: Row) => {
    const st = useStore.getState();
    if (r.kind === "suite") st.select({ kind: "suite", tab: st.sel.kind === "suite" && st.sel.tab !== "hooks" ? st.sel.tab : "general" });
    else if (r.kind === "hook") st.select({ kind: "suite", tab: "hooks", phase: r.hook, step: 0 });
    else if (r.kind === "folder") st.select({ kind: "folder", id: r.id, tab: "settings" });
    else {
      const tc = r.node as TestCase;
      st.select(tc.steps?.length ? { kind: "case", id: r.id, phase: "step", step: 0 } : { kind: "case", id: r.id, tab: "details" });
    }
  };

  const onRowClick = (e: MouseEvent, r: Row) => {
    const st = useStore.getState();
    if (r.kind === "suite" || r.kind === "hook") {
      anchor.current = null;
      return open(r);
    }
    if (e.ctrlKey || e.metaKey) {
      const has = st.multi.includes(r.id);
      const base = st.multi.length ? st.multi : st.sel.id && st.sel.kind !== "suite" ? [st.sel.id] : [];
      st.setMulti(has ? base.filter((x) => x !== r.id) : [...base.filter((x) => x !== r.id), r.id]);
      anchor.current = r.id;
      return;
    }
    if (e.shiftKey && anchor.current) {
      const a = rows.findIndex((x) => x.id === anchor.current);
      const b = rows.findIndex((x) => x.id === r.id);
      if (a >= 0 && b >= 0) {
        const [lo, hi] = a < b ? [a, b] : [b, a];
        st.setMulti(rows.slice(lo, hi + 1).filter((x) => x.kind === "case" || x.kind === "folder").map((x) => x.id));
        return;
      }
    }
    anchor.current = r.id;
    open(r);
  };

  const targets = (r: Row) => {
    const m = useStore.getState().multi;
    return m.includes(r.id) && m.length > 1 ? m : [r.id];
  };

  const menu = (e: MouseEvent, r: Row) => {
    const st = useStore.getState();
    if (r.kind === "suite" || r.kind === "hook") {
      return openContextMenu(e, [
        { label: "Run whole suite", icon: <Play size={14} />, onClick: () => runNodes([]) },
        { label: "Run with options…", icon: <SlidersHorizontal size={14} />, onClick: () => openRunDialog("all") },
        { sep: true },
        { label: "New test case at the top level", icon: <FilePlus size={14} />, onClick: () => addCase({ parentId: null, index: -1 }) },
        { label: "New folder at the top level", icon: <FolderPlus size={14} />, onClick: () => addFolder({ parentId: null, index: -1 }) },
      ]);
    }
    if (!st.multi.includes(r.id)) {
      st.setMulti([r.id]);
      anchor.current = r.id;
    }
    const ids = targets(r);
    const many = ids.length > 1;
    const items: MenuItem[] = [
      { label: many ? `Run ${ids.length} selected` : r.kind === "folder" ? "Run folder" : "Run test case", icon: <Play size={14} />, onClick: () => runNodes(ids) },
    ];
    if (!many && r.kind === "folder") {
      items.push(
        { sep: true },
        { label: "New test case inside", icon: <FilePlus size={14} />, onClick: () => addCase({ parentId: r.id, index: -1 }) },
        { label: "New folder inside", icon: <FolderPlus size={14} />, onClick: () => addFolder({ parentId: r.id, index: -1 }) },
      );
    }
    if (!many && r.kind === "case") {
      items.push({ sep: true }, { label: "New test case below", icon: <FilePlus size={14} />, onClick: () => addCase({ parentId: r.parentId, index: r.index + 1 }) });
    }
    items.push(
      { sep: true },
      { label: "Duplicate", icon: <CopyPlus size={14} />, hint: "Ctrl+D", onClick: () => duplicateNodes(ids) },
      { label: ids.every((id) => find(suite, id)?.node.disabled) ? "Enable" : "Disable", icon: <Power size={14} />, onClick: () => toggleDisabled(ids) },
    );
    if (!many && r.kind === "case") items.push({ label: "Copy key", icon: <Copy size={14} />, onClick: () => copyText((r.node as TestCase).key) });
    if (!many && r.kind === "folder") {
      items.push({
        label: "Collapse everything inside", icon: <ChevronsDownUp size={14} />,
        onClick: () => st.expandMany(allFolders({ ...suite, items: (r.node as Folder).items }).map((x) => x.folder.id), false),
      });
    }
    items.push({ sep: true }, { label: many ? `Delete ${ids.length} items` : "Delete", icon: <Trash2 size={14} />, danger: true, hint: "Del", onClick: () => deleteNodes(ids) });
    openContextMenu(e, items);
  };

  const onKey = (e: KeyboardEvent) => {
    if (isTyping(e.target)) return;
    const st = useStore.getState();
    const i = rows.findIndex((r) => r.id === selectedId);
    const r = rows[i];
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      const n = rows[Math.max(0, Math.min(rows.length - 1, i + (e.key === "ArrowDown" ? 1 : -1)))];
      if (n) {
        anchor.current = n.id;
        open(n);
      }
    } else if (e.key === "ArrowRight" && r?.kind === "folder") {
      e.preventDefault();
      if (!st.expanded[r.id]) st.toggleExpanded(r.id, true);
      else if (rows[i + 1]?.parentId === r.id) open(rows[i + 1]);
    } else if (e.key === "ArrowLeft" && r) {
      e.preventDefault();
      if (r.kind === "folder" && st.expanded[r.id]) st.toggleExpanded(r.id, false);
      else if (r.parentId) open(rows.find((x) => x.id === r.parentId)!);
    } else if ((e.key === "Delete" || (e.key === "Backspace" && e.metaKey)) && r && (r.kind === "case" || r.kind === "folder")) {
      e.preventDefault();
      deleteNodes(targets(r));
    } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "d" && r && (r.kind === "case" || r.kind === "folder")) {
      e.preventDefault();
      duplicateNodes(targets(r));
    } else if (e.key === "Enter" && r?.kind === "folder") {
      e.preventDefault();
      st.toggleExpanded(r.id);
    }
  };

  // ------------------------------------------------------------ drag and drop
  const onDragStart = (e: DragEvent, r: Row) => {
    if (r.kind !== "case" && r.kind !== "folder") return e.preventDefault();
    const ids = targets(r);
    e.dataTransfer.setData("application/x-ct-nodes", JSON.stringify(ids));
    e.dataTransfer.effectAllowed = "move";
  };
  const dropPos = (e: DragEvent, r: Row): "before" | "after" | "inside" => {
    if (r.kind === "suite" || r.kind === "hook") return "inside";
    const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
    const y = e.clientY - rect.top;
    if (r.kind === "folder") return y < rect.height * 0.28 ? "before" : y > rect.height * 0.72 && !expanded[r.id] ? "after" : "inside";
    return y < rect.height / 2 ? "before" : "after";
  };
  const onDragOver = (e: DragEvent, r: Row) => {
    if (!e.dataTransfer.types.includes("application/x-ct-nodes")) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = "move";
    const pos = dropPos(e, r);
    if (!drop || drop.id !== r.id || drop.pos !== pos) setDrop({ id: r.id, pos });
  };
  const onDrop = (e: DragEvent, r: Row) => {
    if (!e.dataTransfer.types.includes("application/x-ct-nodes")) return;
    e.preventDefault();
    setDrop(null);
    let ids: string[] = [];
    try {
      ids = JSON.parse(e.dataTransfer.getData("application/x-ct-nodes"));
    } catch {
      return;
    }
    const pos = dropPos(e, r);
    let parentId: string | null;
    let index: number;
    if (r.kind === "suite" || r.kind === "hook") {
      parentId = null;
      index = -1;
    } else if (pos === "inside") {
      parentId = r.id;
      index = -1;
    } else {
      parentId = r.parentId;
      index = r.index + (pos === "after" ? 1 : 0);
    }
    if (ids.includes(r.id) && pos !== "inside") return;
    const st = useStore.getState();
    st.edit((s) => moveNodes(s, ids, parentId, index));
    if (parentId) st.toggleExpanded(parentId, true);
  };

  return (
    <div className="sidebar">
      <div className="side-head">
        <div className="search">
          <Search size={14} />
          <input ref={searchRef} className="input" placeholder="Search key, name or #tag   ( / )" value={query}
            onChange={(e) => useStore.getState().setTreeSearch(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Escape") {
                useStore.getState().setTreeSearch("");
                listRef.current?.focus();
              } else if (e.key === "ArrowDown") {
                e.preventDefault();
                listRef.current?.focus();
              }
            }} />
          {query ? (
            <button className="btn ghost sm icon" style={{ position: "absolute", right: 2, top: 2 }} onClick={() => useStore.getState().setTreeSearch("")} title="Clear"><X size={13} /></button>
          ) : null}
        </div>
        <div className="side-tools">
          <Button size="sm" variant="ghost" icon={<FilePlus size={14} />} onClick={() => addCase()} title="New test case next to the selection">Case</Button>
          <Button size="sm" variant="ghost" icon={<FolderPlus size={14} />} onClick={() => addFolder()} title="New folder next to the selection">Folder</Button>
          <Button size="sm" variant="ghost" className="right" icon={<ChevronsDownUp size={14} />} title="Collapse all"
            onClick={() => useStore.getState().expandMany(allFolders(suite).map((f) => f.folder.id), false)} />
        </div>
      </div>
      <div className="side-list" ref={listRef} tabIndex={0} onKeyDown={onKey} onScroll={(e) => (savedScroll = (e.target as HTMLElement).scrollTop)}
        onDragLeave={(e) => {
          if (!(e.currentTarget as HTMLElement).contains(e.relatedTarget as globalThis.Node)) setDrop(null);
        }}>
        <div style={{ height: v.getTotalSize(), position: "relative" }}>
          {v.getVirtualItems().map((vi) => {
            const r = rows[vi.index];
            const isSel = r.id === selectedId;
            const isMulti = multi.length > 1 && multi.includes(r.id);
            const off = r.node?.disabled;
            const dropCls = drop?.id === r.id ? ` drop-${drop.pos}` : "";
            return (
              <div key={r.id} data-id={r.id}
                className={`tree-row ${r.kind}${isSel ? " selected" : ""}${isMulti ? " multi" : ""}${off ? " off" : ""}${dropCls}`}
                style={{ position: "absolute", top: 0, left: 0, right: 0, transform: `translateY(${vi.start}px)`, paddingLeft: 6 + r.depth * 14 }}
                draggable={r.kind === "case" || r.kind === "folder"}
                onClick={(e) => onRowClick(e, r)}
                onDoubleClick={() => r.kind === "folder" && useStore.getState().toggleExpanded(r.id)}
                onContextMenu={(e) => menu(e, r)}
                onDragStart={(e) => onDragStart(e, r)}
                onDragOver={(e) => onDragOver(e, r)}
                onDrop={(e) => onDrop(e, r)}
                title={r.kind === "case" ? caseTitle(r.node as TestCase) : undefined}
              >
                <RowBody r={r} open={!!expanded[r.id] || !!query} suite={suite} />
              </div>
            );
          })}
        </div>
      </div>
      <div className="side-foot">
        <span>{query ? `${caseCount} of ${totalCases} found` : `${totalCases} test cases`}</span>
        {multi.length > 1 ? (
          <>
            <span>· {multi.length} selected</span>
            <Button size="sm" className="right" icon={<Play size={13} />} onClick={() => runNodes(multi)}>Run selected</Button>
          </>
        ) : null}
      </div>
    </div>
  );
}

function caseTitle(tc: TestCase): string {
  const parts = [tc.key, tc.name].filter(Boolean).join(" — ");
  return tc.tags?.length ? `${parts}\n#${tc.tags.join(" #")}` : parts;
}

function RowBody({ r, open, suite }: { r: Row; open: boolean; suite: Suite }) {
  if (r.kind === "suite") {
    return (
      <>
        <span className="twisty" />
        <Layers size={14} className="ico" />
        <span className="label" style={{ fontWeight: 600 }}>{suite.name || "Suite"}</span>
        <span className="meta">settings</span>
      </>
    );
  }
  if (r.kind === "hook") {
    return (
      <>
        <span className="twisty" />
        <SlidersHorizontal size={13} className="ico" />
        <span className="label muted">Suite {r.hook}</span>
        <span className="meta">{r.count} step{r.count === 1 ? "" : "s"}</span>
      </>
    );
  }
  if (r.kind === "folder") {
    const f = r.node as Folder;
    const hooks = (f.setup?.length ?? 0) + (f.teardown?.length ?? 0);
    return (
      <>
        <button className="twisty" onClick={(e) => {
          e.stopPropagation();
          useStore.getState().toggleExpanded(r.id);
        }} tabIndex={-1}>{open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}</button>
        {open ? <FolderOpen size={14} className="ico" /> : <FolderIcon size={14} className="ico" />}
        <span className="label">{f.name || "(no name)"}</span>
        <span className="meta">
          {hooks ? <SlidersHorizontal size={11} aria-label="has setup or teardown" /> : null}
          {r.status ? <span className={`status-dot ${r.status}`} /> : null}
          <span>{r.count}</span>
        </span>
      </>
    );
  }
  const tc = r.node as TestCase;
  return (
    <>
      <span className="twisty" />
      {r.status ? <span className={`status-dot ${r.status}`} title={`Last run: ${r.status}`} /> : <FileText size={13} className="ico faint" />}
      <span className="label">
        {tc.key ? <span className="key">{tc.key}</span> : null}
        {tc.name && tc.name !== tc.key ? tc.name : tc.key ? "" : <span className="faint">(no name)</span>}
      </span>
      {tc.dependsOn?.length ? <span className="meta" title={`Runs after ${tc.dependsOn.join(", ")}`}>↳</span> : null}
    </>
  );
}

function parseQuery(q: string): { text: string; tags: string[] } | null {
  const t = q.trim().toLowerCase();
  if (!t) return null;
  const tags: string[] = [];
  const text = t.replace(/(?:^|\s)(?:#|tag:)(\S+)/g, (_, tag: string) => {
    tags.push(tag);
    return " ";
  }).trim();
  return { text, tags };
}

function caseMatches(tc: TestCase, q: { text: string; tags: string[] }): boolean {
  if (q.tags.length && !q.tags.every((t) => (tc.tags ?? []).some((x) => x.toLowerCase().includes(t)))) return false;
  if (!q.text) return true;
  return `${tc.key} ${tc.name}`.toLowerCase().includes(q.text) || (tc.description ?? "").toLowerCase().includes(q.text);
}

function flatten(suite: Suite, expanded: Record<string, boolean>, query: string, statuses: Record<string, string>): Row[] {
  const q = parseQuery(query);
  const rows: Row[] = [{ kind: "suite", id: "suite", depth: 0, parentId: null, index: -1 }];
  if (!q) {
    if (suite.setup?.length) rows.push({ kind: "hook", id: "suite:setup", hook: "setup", depth: 0, parentId: null, index: -1, count: suite.setup.length });
    if (suite.teardown?.length) rows.push({ kind: "hook", id: "suite:teardown", hook: "teardown", depth: 0, parentId: null, index: -1, count: suite.teardown.length });
  }
  const visit = (items: Node[] | undefined, depth: number, parentId: string | null, out: Row[]): { found: number; cases: number; status?: string } => {
    let found = 0;
    let cases = 0;
    let status: string | undefined;
    const worse = (s?: string) => {
      if (s && (!status || (RANK[s] ?? 0) > (RANK[status] ?? 0))) status = s;
    };
    (items ?? []).forEach((n, index) => {
      if (n.type === "case") {
        cases++;
        const st = statuses[n.id];
        worse(st);
        if (!q || caseMatches(n, q)) {
          out.push({ kind: "case", id: n.id, depth, node: n, parentId, index, status: st });
          found++;
        }
      } else {
        const sub: Row[] = [];
        const nameHit = !!q && !q.tags.length && !!q.text && n.name.toLowerCase().includes(q.text);
        const res = visit(n.items, depth + 1, n.id, sub);
        cases += res.cases;
        worse(res.status);
        if (!q || res.found > 0 || nameHit) {
          out.push({ kind: "folder", id: n.id, depth, node: n, parentId, index, count: res.cases, status: res.status });
          if (q || expanded[n.id]) out.push(...sub);
          found += res.found + (nameHit ? 1 : 0);
        }
      }
    });
    return { found, cases, status };
  };
  visit(suite.items, 0, null, rows);
  return rows;
}

function countAll(items: Node[] | undefined): number {
  let n = 0;
  for (const x of items ?? []) n += x.type === "case" ? 1 : countAll(x.items);
  return n;
}
