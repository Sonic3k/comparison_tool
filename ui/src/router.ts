// Keeps the address bar in step with what is on screen, so Back and Forward return to the previous place.
import { useStore } from "./store";
import type { RunSel, Sel } from "./store";
import type { Phase } from "./suiteOps";
import { find } from "./suiteOps";

const PHASES = ["pre", "step", "cleanup", "setup", "teardown"];

interface Route {
  view: "edit" | "runs";
  sel?: Sel;
  runId?: string;
  runSel?: RunSel;
}

function enc(s: string) {
  return encodeURIComponent(s);
}

export function toHash(s: { view: string; sel: Sel; activeRun?: string; runSel: RunSel; suite: unknown }): string {
  if (s.view === "runs") {
    let h = "#/runs";
    if (s.activeRun) {
      h += `/${enc(s.activeRun)}`;
      if (s.runSel.caseId) h += `/case/${enc(s.runSel.caseId)}`;
      else if (s.runSel.hook) h += `/hook/${enc(s.runSel.hook)}`;
      if ((s.runSel.caseId || s.runSel.hook) && s.runSel.step !== undefined) h += `/${s.runSel.step}`;
    }
    return h;
  }
  if (!s.suite) return "#/edit";
  const sel = s.sel;
  let h = sel.kind === "suite" ? `#/edit/suite/${sel.tab ?? "general"}` : `#/edit/${sel.kind}/${enc(sel.id ?? "")}`;
  if (sel.phase) h += `/${sel.phase}/${sel.step ?? 0}`;
  else if (sel.kind !== "suite" && sel.tab) h += `/${sel.tab}`;
  return h;
}

export function parseHash(hash: string): Route | null {
  const parts = hash.replace(/^#\/?/, "").split("/").map(decodeURIComponent);
  if (parts[0] === "runs") {
    const r: Route = { view: "runs" };
    if (parts[1]) {
      r.runId = parts[1];
      r.runSel = {};
      if (parts[2] === "case" && parts[3]) r.runSel.caseId = parts[3];
      if (parts[2] === "hook" && parts[3]) r.runSel.hook = parts[3];
      if (parts[4] !== undefined && /^\d+$/.test(parts[4])) r.runSel.step = Number(parts[4]);
    }
    return r;
  }
  if (parts[0] === "edit") {
    const kind = parts[1];
    if (kind === "suite") {
      const sel: Sel = { kind: "suite", tab: parts[2] || "general" };
      if (PHASES.includes(parts[3])) {
        sel.phase = parts[3] as Phase;
        sel.step = Number(parts[4] ?? 0) || 0;
      }
      return { view: "edit", sel };
    }
    if ((kind === "folder" || kind === "case") && parts[2]) {
      const sel: Sel = { kind, id: parts[2] };
      if (PHASES.includes(parts[3])) {
        sel.phase = parts[3] as Phase;
        sel.step = Number(parts[4] ?? 0) || 0;
      } else if (parts[3]) sel.tab = parts[3];
      else if (kind === "folder") sel.tab = "settings";
      else sel.tab = "details";
      return { view: "edit", sel };
    }
    return { view: "edit" };
  }
  return null;
}

function entityOf(s: { view: string; sel: Sel; activeRun?: string; runSel: RunSel }): string {
  if (s.view === "runs") return `runs|${s.activeRun ?? ""}|${s.runSel.caseId ?? s.runSel.hook ?? ""}`;
  return `edit|${s.sel.kind}|${s.sel.id ?? s.sel.tab ?? ""}`;
}

let applying = false;

/** Shows what the address names, when it still exists. */
export function applyHash(hash = location.hash) {
  const r = parseHash(hash);
  if (!r) return;
  const st = useStore.getState();
  applying = true;
  try {
    if (r.view === "edit") {
      if (r.sel && st.suite && (r.sel.kind === "suite" || (r.sel.id && find(st.suite, r.sel.id)))) {
        if (r.sel.id) {
          const loc = find(st.suite, r.sel.id);
          if (loc) st.expandMany(loc.path.map((f) => f.id), true);
        }
        useStore.setState({ view: "edit", sel: r.sel, multi: r.sel.id ? [r.sel.id] : [] });
      } else {
        useStore.setState({ view: "edit" });
      }
    } else {
      const patch: Partial<ReturnType<typeof useStore.getState>> = { view: "runs" };
      if (r.runId && st.runs.some((x) => x.runId === r.runId)) {
        patch.activeRun = r.runId;
        patch.runSel = r.runSel ?? {};
      }
      useStore.setState(patch);
    }
  } finally {
    applying = false;
  }
}

export function startRouter(): () => void {
  let lastEntity = entityOf(useStore.getState());
  const onPop = () => {
    applyHash();
    lastEntity = entityOf(useStore.getState());
  };
  window.addEventListener("popstate", onPop);
  const unsub = useStore.subscribe((s, prev) => {
    if (applying) return;
    if (s.view === prev.view && s.sel === prev.sel && s.activeRun === prev.activeRun && s.runSel === prev.runSel && !!s.suite === !!prev.suite) return;
    const h = toHash(s);
    if (h === location.hash) return;
    const entity = entityOf(s);
    if (entity !== lastEntity) history.pushState(null, "", h);
    else history.replaceState(null, "", h);
    lastEntity = entity;
  });
  return () => {
    window.removeEventListener("popstate", onPop);
    unsub();
  };
}
