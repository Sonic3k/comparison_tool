package com.fpt.comparison_tool.suite;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fpt.comparison_tool.engine.AssertionEvaluator;
import com.fpt.comparison_tool.model.AuthProfile;
import com.fpt.comparison_tool.model.Defaults;
import com.fpt.comparison_tool.model.Environment;
import com.fpt.comparison_tool.model.Extract;
import com.fpt.comparison_tool.model.Folder;
import com.fpt.comparison_tool.model.Node;
import com.fpt.comparison_tool.model.Step;
import com.fpt.comparison_tool.model.Suite;
import com.fpt.comparison_tool.model.TestCase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Finds problems in a suite before it runs: unknown references, duplicate keys, dependency cycles, bad assertions. */
public final class SuiteValidator {

    /**
     * level: error (the run would fail or misbehave) or warning. where: a readable location. nodeId, section and step
     * point the editor at the place: nodeId empty = the suite; section general, environments, auth, defaults, setup,
     * teardown (suite and folders) or pre, step, cleanup, details (test cases); step is 0-based.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Issue(String level, String where, String message, String nodeId, String section, Integer step) {
        public Issue(String level, String where, String message) {
            this(level, where, message, null, null, null);
        }
    }

    private SuiteValidator() {
    }

    public static List<Issue> validate(Suite suite) {
        List<Issue> out = new ArrayList<>();
        Set<String> envNames = new HashSet<>();
        for (Environment e : suite.environments()) {
            if (e.name().isBlank()) out.add(at(error("Environments", "An environment has no name"), null, "environments", null));
            else if (!envNames.add(e.name())) out.add(at(error("Environments", "Duplicate environment '" + e.name() + "'"), null, "environments", null));
            if (e.auth() != null) checkAuth(suite, e.auth(), "Environment " + e.name(), null, "environments", null, out);
        }
        if (suite.environments().isEmpty()) out.add(at(error("Environments", "The suite has no environment"), null, "environments", null));
        Set<String> profiles = new HashSet<>();
        for (AuthProfile p : suite.authProfiles()) {
            if (!profiles.add(p.name())) out.add(at(error("Auth profiles", "Duplicate auth profile '" + p.name() + "'"), null, "auth", null));
        }
        String env = suite.settingsOrDefault().env();
        if (env != null && !envNames.contains(env)) out.add(at(error("Settings", "Default environment '" + env + "' does not exist"), null, "general", null));
        String base = suite.settingsOrDefault().baselineEnv();
        if (base != null && !envNames.contains(base)) out.add(at(error("Settings", "Baseline environment '" + base + "' does not exist"), null, "general", null));

        checkDefaults(suite, suite.defaultsOrEmpty(), "Suite defaults", null, out);
        checkSteps(suite, suite.setup(), "Suite setup", null, "setup", out);
        checkSteps(suite, suite.teardown(), "Suite teardown", null, "teardown", out);
        Map<String, Integer> keysEverywhere = new HashMap<>();
        checkItems(suite, suite.items(), "", out, keysEverywhere, null);
        keysEverywhere.forEach((k, n) -> {
            if (n > 1 && !k.isBlank()) out.add(warning("Test cases", "Key '" + k + "' is used by " + n + " test cases; --case " + k + " selects all of them"));
        });
        return out;
    }

    private static void checkItems(Suite suite, List<Node> items, String path, List<Issue> out, Map<String, Integer> keys,
                                   String folderId) {
        Map<String, TestCase> byKey = new HashMap<>();
        for (Node n : items) {
            if (n instanceof TestCase tc) {
                String where = (path.isEmpty() ? "" : path + " / ") + tc.label();
                keys.merge(tc.key().toLowerCase(Locale.ROOT), 1, Integer::sum);
                if (tc.key().isBlank()) out.add(at(warning(where, "The test case has no key"), tc.id(), "details", null));
                else if (byKey.put(tc.key().toLowerCase(Locale.ROOT), tc) != null) {
                    out.add(at(error(where, "Another test case of this folder has the key '" + tc.key() + "'"), tc.id(), "details", null));
                }
                if (tc.steps().isEmpty()) out.add(at(warning(where, "The test case has no steps"), tc.id(), "step", null));
                checkSteps(suite, tc.preconditions(), where + " (precondition)", tc.id(), "pre", out);
                checkSteps(suite, tc.steps(), where, tc.id(), "step", out);
                checkSteps(suite, tc.cleanup(), where + " (cleanup)", tc.id(), "cleanup", out);
            } else if (n instanceof Folder f) {
                String where = path.isEmpty() ? f.name() : path + "/" + f.name();
                if (f.name().isBlank()) out.add(at(warning(where, "A folder has no name"), f.id(), "general", null));
                checkDefaults(suite, f.defaultsOrEmpty(), where + " (defaults)", f.id(), out);
                checkSteps(suite, f.setup(), where + " (setup)", f.id(), "setup", out);
                checkSteps(suite, f.teardown(), where + " (teardown)", f.id(), "teardown", out);
                checkItems(suite, f.items(), where, out, keys, f.id());
            }
        }
        for (Node n : items) {
            if (!(n instanceof TestCase tc)) continue;
            for (String dep : tc.dependsOn()) {
                if (dep.equalsIgnoreCase(tc.key())) {
                    out.add(at(error(tc.label(), "A test case cannot depend on itself"), tc.id(), "details", null));
                } else if (!byKey.containsKey(dep.toLowerCase(Locale.ROOT))) {
                    out.add(at(error(tc.label(), "dependsOn '" + dep + "' is not a test case of the same folder"), tc.id(), "details", null));
                }
            }
        }
        String cycle = cycle(byKey);
        if (cycle != null) out.add(at(error(path.isEmpty() ? "Root" : path, "dependsOn cycle: " + cycle), folderId, "general", null));
    }

    private static String cycle(Map<String, TestCase> byKey) {
        Map<String, Integer> state = new HashMap<>();
        for (String k : byKey.keySet()) {
            String c = dfs(k, byKey, state, new ArrayList<>());
            if (c != null) return c;
        }
        return null;
    }

    private static String dfs(String key, Map<String, TestCase> byKey, Map<String, Integer> state, List<String> stack) {
        Integer s = state.get(key);
        if (s != null && s == 2) return null;
        if (s != null && s == 1) return String.join(" -> ", stack) + " -> " + key;
        TestCase tc = byKey.get(key);
        if (tc == null) return null;
        state.put(key, 1);
        stack.add(tc.key());
        for (String d : tc.dependsOn()) {
            String c = dfs(d.toLowerCase(Locale.ROOT), byKey, state, stack);
            if (c != null) return c;
        }
        stack.remove(stack.size() - 1);
        state.put(key, 2);
        return null;
    }

    private static void checkDefaults(Suite suite, Defaults d, String where, String nodeId, List<Issue> out) {
        if (d.auth() != null) checkAuth(suite, d.auth(), where, nodeId, "defaults", null, out);
    }

    private static void checkSteps(Suite suite, List<Step> steps, String where, String nodeId, String section, List<Issue> out) {
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            String w = where + " step " + (i + 1) + (s.name().isBlank() ? "" : " '" + s.name() + "'");
            if (s.url().isBlank()) out.add(at(error(w, "The step has no URL"), nodeId, section, i));
            if (s.auth() != null) checkAuth(suite, s.auth(), w, nodeId, section, i, out);
            for (Extract e : s.extract()) {
                if (!e.path().startsWith("$")) out.add(at(error(w, "Extract '" + e.name() + "': path must start with $"), nodeId, section, i));
            }
            if (s.expect() != null) {
                for (String a : s.expect().assertions()) {
                    String problem = AssertionEvaluator.checkSyntax(a);
                    if (problem != null) out.add(at(warning(w, "Assertion '" + a + "': " + problem), nodeId, section, i));
                }
            }
        }
    }

    private static void checkAuth(Suite suite, String name, String where, String nodeId, String section, Integer step,
                                  List<Issue> out) {
        if (name.equalsIgnoreCase(AuthProfile.NONE)) return;
        if (suite.authProfile(name) == null) out.add(at(error(where, "Unknown auth profile '" + name + "'"), nodeId, section, step));
    }

    private static Issue at(Issue i, String nodeId, String section, Integer step) {
        return new Issue(i.level(), i.where(), i.message(), nodeId, section, step);
    }

    private static Issue error(String where, String message) {
        return new Issue("error", where, message);
    }

    private static Issue warning(String where, String message) {
        return new Issue("warning", where, message);
    }
}
