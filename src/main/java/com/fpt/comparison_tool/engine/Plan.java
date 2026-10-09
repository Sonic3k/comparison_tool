package com.fpt.comparison_tool.engine;

import com.fpt.comparison_tool.model.Defaults;
import com.fpt.comparison_tool.model.Folder;
import com.fpt.comparison_tool.model.Node;
import com.fpt.comparison_tool.model.RunPolicy;
import com.fpt.comparison_tool.model.Step;
import com.fpt.comparison_tool.model.Suite;
import com.fpt.comparison_tool.model.TestCase;
import com.fpt.comparison_tool.result.CaseResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** The part of a suite a run will execute: selected test cases, their dependencies and the folders around them. */
public final class Plan {

    /** A folder (or the suite root) that contains at least one planned test case. */
    public static final class PFolder {
        final PFolder parent;
        final Folder folder;
        final String id;
        final List<String> path;
        final boolean casesParallel;
        final boolean foldersParallel;
        final List<Defaults> defaults;
        final List<PCase> cases = new ArrayList<>();
        final List<PFolder> folders = new ArrayList<>();

        PFolder(PFolder parent, Folder folder, List<String> path, boolean casesParallel, boolean foldersParallel,
                List<Defaults> defaults) {
            this.parent = parent;
            this.folder = folder;
            this.id = folder == null ? "" : folder.id();
            this.path = path;
            this.casesParallel = casesParallel;
            this.foldersParallel = foldersParallel;
            this.defaults = defaults;
        }

        boolean root() {
            return folder == null;
        }

        String hookKey() {
            return root() ? "suite" : id;
        }

        List<PCase> allCases() {
            List<PCase> all = new ArrayList<>(cases);
            for (PFolder f : folders) all.addAll(f.allCases());
            return all;
        }
    }

    /** A planned test case. */
    public static final class PCase {
        final TestCase tc;
        final PFolder folder;
        final int order;
        final List<PCase> deps = new ArrayList<>();
        final CompletableFuture<CaseResult> done = new CompletableFuture<>();

        PCase(TestCase tc, PFolder folder, int order) {
            this.tc = tc;
            this.folder = folder;
            this.order = order;
        }
    }

    /** The selection cannot be planned (unknown dependency, cycle, nothing selected). */
    public static final class PlanException extends RuntimeException {
        public PlanException(String message) {
            super(message);
        }
    }

    /**
     * A test case of the selection's scope that is not planned because it, or a folder above it, is disabled.
     * position counts the planned test cases before it in suite order; folder names the disabled folder, if any.
     */
    public record Disabled(TestCase tc, List<String> path, String folderId, int position, String folder) {
    }

    final PFolder root;
    final List<PCase> cases;
    final List<Disabled> disabled;

    private Plan(PFolder root, List<PCase> cases, List<Disabled> disabled) {
        this.root = root;
        this.cases = cases;
        this.disabled = disabled;
    }

    public List<String> caseIds() {
        return cases.stream().map(c -> c.tc.id()).toList();
    }

    public List<Disabled> disabled() {
        return disabled;
    }

    public int size() {
        return cases.size();
    }

    // ---------------------------------------------------------------- building

    private static final class Entry {
        final TestCase tc;
        final List<Folder> chain;
        final boolean effectiveActive;
        final int order;

        Entry(TestCase tc, List<Folder> chain, boolean effectiveActive, int order) {
            this.tc = tc;
            this.chain = chain;
            this.effectiveActive = effectiveActive;
            this.order = order;
        }
    }

    public static Plan build(Suite suite, Selection selection) {
        List<Entry> entries = new ArrayList<>();
        Map<String, List<Folder>> folderChains = new HashMap<>();
        collect(suite.items(), new ArrayList<>(), true, entries, folderChains);

        Set<Entry> selected = new LinkedHashSet<>();
        Set<String> tags = lower(selection.tags());
        Set<String> folderIds = new HashSet<>(selection.folderIds());
        Set<String> folderPaths = lower(selection.folderPaths());
        if (selection.everything()) {
            for (Entry e : entries) if (e.effectiveActive) selected.add(e);
        } else {
            Set<String> ids = new HashSet<>(selection.caseIds());
            Set<String> keys = lower(selection.caseKeys());
            for (Entry e : entries) {
                if (ids.contains(e.tc.id()) || keys.contains(e.tc.key().toLowerCase(Locale.ROOT))) {
                    selected.add(e);
                    continue;
                }
                int hit = folderHit(e.chain, folderIds, folderPaths);
                if (hit >= 0 && e.tc.active() && chainActiveBelow(e.chain, hit)) {
                    selected.add(e);
                    continue;
                }
                if (!tags.isEmpty() && e.effectiveActive && e.tc.tags().stream().anyMatch(t -> tags.contains(t.toLowerCase(Locale.ROOT)))) {
                    selected.add(e);
                }
            }
        }

        // add dependencies (same folder, by key), transitively
        Map<Entry, List<Entry>> deps = new HashMap<>();
        List<Entry> queue = new ArrayList<>(selected);
        for (int i = 0; i < queue.size(); i++) {
            Entry e = queue.get(i);
            List<Entry> resolved = new ArrayList<>();
            for (String key : e.tc.dependsOn()) {
                Entry dep = entries.stream()
                        .filter(o -> o != e && sameFolder(o, e) && o.tc.key().equalsIgnoreCase(key))
                        .findFirst()
                        .orElseThrow(() -> new PlanException("Test case " + e.tc.label() + " depends on '" + key
                                + "', which is not a test case of the same folder"));
                resolved.add(dep);
                if (selected.add(dep)) queue.add(dep);
            }
            deps.put(e, resolved);
        }
        checkCycles(deps);
        if (selected.isEmpty()) throw new PlanException("Nothing to run: no enabled test case matches the selection");

        // folder tree in suite order
        PFolder root = new PFolder(null, null, List.of(),
                suite.settingsOrDefault().runOrDefault().casesParallel(true),
                suite.settingsOrDefault().runOrDefault().foldersParallel(false),
                List.of(suite.defaultsOrEmpty()));
        Map<String, PFolder> byFolderId = new HashMap<>();
        Map<Entry, PCase> planned = new HashMap<>();
        List<PCase> ordered = new ArrayList<>();
        for (Entry e : entries) {
            if (!selected.contains(e)) continue;
            PFolder parent = root;
            for (Folder f : e.chain) {
                PFolder existing = byFolderId.get(f.id());
                if (existing == null) {
                    List<String> path = new ArrayList<>(parent.path);
                    path.add(f.name());
                    List<Defaults> defs = new ArrayList<>(parent.defaults);
                    defs.add(f.defaultsOrEmpty());
                    RunPolicy p = f.runOrDefault();
                    existing = new PFolder(parent, f, List.copyOf(path), p.casesParallel(parent.casesParallel),
                            p.foldersParallel(parent.foldersParallel), List.copyOf(defs));
                    parent.folders.add(existing);
                    byFolderId.put(f.id(), existing);
                }
                parent = existing;
            }
            PCase pc = new PCase(e.tc, parent, e.order);
            parent.cases.add(pc);
            planned.put(e, pc);
            ordered.add(pc);
        }
        for (Map.Entry<Entry, List<Entry>> d : deps.entrySet()) {
            PCase pc = planned.get(d.getKey());
            if (pc == null) continue;
            for (Entry dep : d.getValue()) pc.deps.add(planned.get(dep));
        }

        // disabled test cases of the scope, so results can list them where they sit
        List<Disabled> disabled = new ArrayList<>();
        int before = 0;
        for (Entry e : entries) {
            if (selected.contains(e)) {
                before++;
                continue;
            }
            int hit = folderHit(e.chain, folderIds, folderPaths);
            boolean inScope = selection.everything() || hit >= 0
                    || (!tags.isEmpty() && e.tc.tags().stream().anyMatch(t -> tags.contains(t.toLowerCase(Locale.ROOT))));
            if (!inScope) continue;
            String off = null;
            for (int i = Math.max(0, hit + 1); i < e.chain.size(); i++) {
                if (!e.chain.get(i).active()) {
                    off = e.chain.get(i).name();
                    break;
                }
            }
            if (off == null && e.tc.active()) continue;
            List<String> path = e.chain.stream().map(Folder::name).toList();
            String folderId = e.chain.isEmpty() ? null : e.chain.get(e.chain.size() - 1).id();
            disabled.add(new Disabled(e.tc, path, folderId, before, off));
        }
        return new Plan(root, List.copyOf(ordered), List.copyOf(disabled));
    }

    /** Test cases of a folder in an order where every dependency comes first, otherwise suite order. */
    static List<PCase> serialOrder(List<PCase> cases) {
        List<PCase> out = new ArrayList<>();
        Set<PCase> done = new HashSet<>();
        for (PCase c : cases) visit(c, cases, done, out);
        return out;
    }

    private static void visit(PCase c, List<PCase> scope, Set<PCase> done, List<PCase> out) {
        if (done.contains(c)) return;
        done.add(c);
        for (PCase d : c.deps) if (scope.contains(d)) visit(d, scope, done, out);
        out.add(c);
    }

    private static void collect(List<Node> items, List<Folder> chain, boolean chainActive, List<Entry> out,
                                Map<String, List<Folder>> chains) {
        for (Node n : items) {
            if (n instanceof Folder f) {
                List<Folder> next = new ArrayList<>(chain);
                next.add(f);
                chains.put(f.id(), next);
                collect(f.items(), next, chainActive && f.active(), out, chains);
            } else if (n instanceof TestCase tc) {
                out.add(new Entry(tc, List.copyOf(chain), chainActive && tc.active(), out.size()));
            }
        }
    }

    private static int folderHit(List<Folder> chain, Set<String> ids, Set<String> paths) {
        if (ids.isEmpty() && paths.isEmpty()) return -1;
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < chain.size(); i++) {
            Folder f = chain.get(i);
            if (i > 0) path.append('/');
            path.append(f.name());
            if (ids.contains(f.id()) || paths.contains(path.toString().toLowerCase(Locale.ROOT))) return i;
        }
        return -1;
    }

    private static boolean chainActiveBelow(List<Folder> chain, int from) {
        for (int i = from + 1; i < chain.size(); i++) if (!chain.get(i).active()) return false;
        return true;
    }

    private static boolean sameFolder(Entry a, Entry b) {
        String fa = a.chain.isEmpty() ? "" : a.chain.get(a.chain.size() - 1).id();
        String fb = b.chain.isEmpty() ? "" : b.chain.get(b.chain.size() - 1).id();
        return fa.equals(fb);
    }

    private static Set<String> lower(List<String> values) {
        Set<String> out = new HashSet<>();
        for (String v : values) if (v != null && !v.isBlank()) out.add(v.trim().toLowerCase(Locale.ROOT));
        return out;
    }

    private static void checkCycles(Map<Entry, List<Entry>> deps) {
        Map<Entry, Integer> state = new HashMap<>();
        for (Entry e : deps.keySet()) dfs(e, deps, state, new ArrayList<>());
    }

    private static void dfs(Entry e, Map<Entry, List<Entry>> deps, Map<Entry, Integer> state, List<Entry> stack) {
        Integer s = state.get(e);
        if (s != null && s == 2) return;
        if (s != null && s == 1) {
            List<String> cycle = stack.stream().map(x -> x.tc.label()).toList();
            throw new PlanException("dependsOn cycle: " + String.join(" -> ", cycle) + " -> " + e.tc.label());
        }
        state.put(e, 1);
        stack.add(e);
        for (Entry d : deps.getOrDefault(e, List.of())) dfs(d, deps, state, stack);
        stack.remove(stack.size() - 1);
        state.put(e, 2);
    }

    static List<Step> setupOf(Suite suite, PFolder f) {
        return f.root() ? suite.setup() : f.folder.setup();
    }

    static List<Step> teardownOf(Suite suite, PFolder f) {
        return f.root() ? suite.teardown() : f.folder.teardown();
    }
}
