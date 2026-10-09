// The results screen's data for the current store state, cached on its inputs so the list, the panels and the
// keyboard handler all see the same rows without computing them twice.
import { useStore } from "../store";
import { find } from "../suiteOps";
import type { RunView } from "../runs";
import type { PastRun } from "./history";
import { allPast, compareTarget } from "./history";
import type { CaseInfo, Change, Counts, Row } from "./model";
import { buildRows, caseInfos, countChanges, countStates, matchesFilter, searcher, withNames } from "./model";

export interface ResultsData {
  run?: RunView;
  infos: CaseInfo[];
  rows: Row[];
  counts: Counts;
  changes: Record<Change, number>;
  past: PastRun[];
  compared?: PastRun;
  /** Case rows in the order shown, for next / previous. */
  order: string[];
  /** Every test case that matches the filter and search, shown or inside a closed group. */
  matched: string[];
}

type St = ReturnType<typeof useStore.getState>;

let pastKey: unknown[] = [];
let pastVal: PastRun[] = [];
let infoKey: unknown[] = [];
let infoVal: CaseInfo[] = [];
let countVal: Counts = countStates([]);
let changeVal: Record<Change, number> = countChanges([]);
let rowKey: unknown[] = [];
let rowVal: { rows: Row[]; order: string[]; matched: string[] } = { rows: [], order: [], matched: [] };

function same(a: unknown[], b: unknown[]) {
  return a.length === b.length && a.every((x, i) => x === b[i]);
}

export function computeResults(st: St): ResultsData {
  const run = st.runs.find((r) => r.runId === st.activeRun);
  const pk = [st.pastRuns, st.runs];
  if (!same(pk, pastKey)) {
    pastKey = pk;
    pastVal = allPast(st.pastRuns, st.runs);
  }
  const past = pastVal;
  const compared = run ? compareTarget(run, past, st.compareWith) : undefined;
  const ik = [run, compared, st.suite];
  if (!same(ik, infoKey)) {
    infoKey = ik;
    const suite = st.suite;
    infoVal = run ? withNames(caseInfos(run, compared?.statuses), (id) => {
      const loc = suite ? find(suite, id) : null;
      if (!loc || loc.node.type !== "case") return null;
      return { key: loc.node.key, name: loc.node.name, path: loc.path.map((f) => f.name), folderId: loc.path[loc.path.length - 1]?.id };
    }) : [];
    countVal = countStates(infoVal);
    changeVal = countChanges(infoVal);
  }
  const infos = infoVal;
  const rk = [run, infos, st.filter, st.delta, st.group, st.sortBy, st.search, st.searchBodies, st.groupOpen];
  if (!same(rk, rowKey)) {
    rowKey = rk;
    const rows = run ? buildRows(run, infos, {
      filter: st.filter, delta: st.delta, group: st.group, sort: st.sortBy, search: st.search, searchBodies: st.searchBodies, open: st.groupOpen,
    }) : [];
    const search = searcher(st.search, st.searchBodies);
    const matched = infos.filter((i) => matchesFilter(i, st.filter, st.delta) && (!search || search(i))).map((i) => i.id);
    rowVal = { rows, order: rows.filter((r) => r.kind === "case").map((r) => r.id), matched };
  }
  return { run, infos, rows: rowVal.rows, order: rowVal.order, matched: rowVal.matched, counts: countVal, changes: changeVal, past, compared };
}

export function useResults(): ResultsData {
  const st = useStore();
  return computeResults(st);
}
