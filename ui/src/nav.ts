import { useStore } from "./store";
import type { Phase } from "./suiteOps";
import { find } from "./suiteOps";
import type { Issue } from "./types";

/** Selects a node in the editor, opening its folders in the tree. */
export function reveal(id: string) {
  const st = useStore.getState();
  if (!st.suite) return;
  const loc = find(st.suite, id);
  if (!loc) return;
  st.expandMany(loc.path.map((f) => f.id), true);
}

export function openCase(id: string, phase: Phase = "step", step = 0) {
  const st = useStore.getState();
  if (!st.suite || !find(st.suite, id)) {
    st.toast("error", "This test case is not in the open suite any more");
    return false;
  }
  reveal(id);
  st.select({ kind: "case", id, phase, step });
  return true;
}

export function openFolder(id: string, phase?: Phase, step?: number) {
  const st = useStore.getState();
  if (!st.suite || !find(st.suite, id)) {
    st.toast("error", "This folder is not in the open suite any more");
    return false;
  }
  reveal(id);
  st.select(phase ? { kind: "folder", id, phase, step: step ?? 0 } : { kind: "folder", id, tab: "settings" });
  return true;
}

export function goToIssue(i: Issue) {
  const st = useStore.getState();
  if (!st.suite) return;
  const sec = i.section ?? "";
  if (i.nodeId) {
    const loc = find(st.suite, i.nodeId);
    if (!loc) return;
    if (loc.node.type === "case") {
      if (sec === "pre" || sec === "step" || sec === "cleanup") openCase(i.nodeId, sec, i.step ?? 0);
      else {
        reveal(i.nodeId);
        st.select({ kind: "case", id: i.nodeId, tab: "details" });
      }
    } else if (sec === "setup" || sec === "teardown") openFolder(i.nodeId, sec, i.step ?? 0);
    else openFolder(i.nodeId);
    return;
  }
  if (sec === "setup" || sec === "teardown") st.select({ kind: "suite", tab: "hooks", phase: sec, step: i.step ?? 0 });
  else st.select({ kind: "suite", tab: sec || "general" });
}
