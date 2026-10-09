package com.fpt.comparison_tool.suite;

import com.fpt.comparison_tool.model.Defaults;
import com.fpt.comparison_tool.model.Folder;
import com.fpt.comparison_tool.model.KeyValue;
import com.fpt.comparison_tool.model.Node;
import com.fpt.comparison_tool.model.Step;
import com.fpt.comparison_tool.model.Suite;
import com.fpt.comparison_tool.model.TestCase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Moves a header that every step of a folder sends with the same value into the folder defaults, and a header every
 * folder then shares into the suite defaults. The value each step sends does not change.
 */
final class HeaderHoist {

    private HeaderHoist() {
    }

    static Suite apply(Suite suite, Map<String, Integer> counters) {
        List<Node> items = new ArrayList<>();
        for (Node n : suite.items()) {
            if (n instanceof Folder f) items.add(hoistFolder(f, counters));
            else items.add(n);
        }
        // suite level: headers all folders share in their defaults, also present on every suite hook step
        List<Folder> folders = items.stream().filter(n -> n instanceof Folder).map(n -> (Folder) n).toList();
        boolean looseCases = items.stream().anyMatch(n -> n instanceof TestCase);
        Map<String, KeyValue> common = null;
        if (!folders.isEmpty() && !looseCases) {
            for (Folder f : folders) {
                Map<String, KeyValue> h = index(f.defaultsOrEmpty().headers());
                if (common == null) common = h;
                else common.entrySet().removeIf(e -> !e.getValue().equals(h.get(e.getKey())));
            }
            List<Step> hooks = new ArrayList<>(suite.setup());
            hooks.addAll(suite.teardown());
            for (Step s : hooks) {
                Map<String, KeyValue> h = index(s.headers());
                common.entrySet().removeIf(e -> !e.getValue().equals(h.get(e.getKey())));
            }
        }
        if (common == null || common.isEmpty()) {
            return copy(suite, suite.defaults(), suite.setup(), suite.teardown(), items);
        }
        Map<String, KeyValue> shared = common;
        counters.put("headers moved to the suite defaults", shared.size());
        List<Node> stripped = new ArrayList<>();
        for (Node n : items) {
            Folder f = (Folder) n;
            Defaults d = f.defaultsOrEmpty();
            List<KeyValue> rest = d.headers().stream().filter(h -> !shared.containsKey(lower(h.key()))).toList();
            stripped.add(new Folder(f.id(), f.name(), f.description(), f.disabled(), new Defaults(rest, d.auth(), d.timeoutMs()),
                    f.run(), f.setup(), f.teardown(), f.items()));
        }
        Defaults sd = suite.defaultsOrEmpty();
        List<KeyValue> suiteHeaders = new ArrayList<>(sd.headers());
        suiteHeaders.addAll(shared.values());
        return copy(suite, new Defaults(suiteHeaders, sd.auth(), sd.timeoutMs()), strip(suite.setup(), shared),
                strip(suite.teardown(), shared), stripped);
    }

    private static Folder hoistFolder(Folder f, Map<String, Integer> counters) {
        List<Step> all = new ArrayList<>(f.setup());
        all.addAll(f.teardown());
        collect(f.items(), all);
        if (all.size() < 2) return f;
        Map<String, KeyValue> common = null;
        for (Step s : all) {
            Map<String, KeyValue> h = index(s.headers());
            if (common == null) common = h;
            else common.entrySet().removeIf(e -> !e.getValue().equals(h.get(e.getKey())));
            if (common.isEmpty()) return f;
        }
        Map<String, KeyValue> shared = common;
        counters.merge("headers moved to folder defaults", shared.size(), Integer::sum);
        UnaryOperator<List<Step>> s = steps -> strip(steps, shared);
        Defaults d = f.defaultsOrEmpty();
        List<KeyValue> headers = new ArrayList<>(d.headers());
        headers.addAll(shared.values());
        return new Folder(f.id(), f.name(), f.description(), f.disabled(), new Defaults(headers, d.auth(), d.timeoutMs()),
                f.run(), s.apply(f.setup()), s.apply(f.teardown()), stripItems(f.items(), shared));
    }

    private static List<Node> stripItems(List<Node> items, Map<String, KeyValue> shared) {
        List<Node> out = new ArrayList<>();
        for (Node n : items) {
            if (n instanceof TestCase tc) {
                out.add(new TestCase(tc.id(), tc.key(), tc.name(), tc.description(), tc.tags(), tc.disabled(), tc.dependsOn(),
                        tc.stopOnFailure(), strip(tc.preconditions(), shared), strip(tc.steps(), shared), strip(tc.cleanup(), shared)));
            } else if (n instanceof Folder f) {
                out.add(new Folder(f.id(), f.name(), f.description(), f.disabled(), f.defaults(), f.run(),
                        strip(f.setup(), shared), strip(f.teardown(), shared), stripItems(f.items(), shared)));
            }
        }
        return out;
    }

    private static void collect(List<Node> items, List<Step> out) {
        for (Node n : items) {
            if (n instanceof TestCase tc) {
                out.addAll(tc.preconditions());
                out.addAll(tc.steps());
                out.addAll(tc.cleanup());
            } else if (n instanceof Folder f) {
                out.addAll(f.setup());
                out.addAll(f.teardown());
                collect(f.items(), out);
            }
        }
    }

    private static List<Step> strip(List<Step> steps, Map<String, KeyValue> shared) {
        List<Step> out = new ArrayList<>(steps.size());
        for (Step s : steps) {
            List<KeyValue> rest = s.headers().stream().filter(h -> !shared.containsKey(lower(h.key()))).toList();
            out.add(new Step(s.name(), s.disabled(), s.method(), s.url(), s.query(), rest, s.body(), s.auth(), s.extract(),
                    s.expect(), s.options(), s.notes()));
        }
        return out;
    }

    private static Map<String, KeyValue> index(List<KeyValue> headers) {
        Map<String, KeyValue> m = new LinkedHashMap<>();
        for (KeyValue h : headers) if (h.active()) m.put(lower(h.key()), KeyValue.of(h.key().trim(), h.value()));
        return m;
    }

    private static String lower(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }

    private static Suite copy(Suite s, Defaults defaults, List<Step> setup, List<Step> teardown, List<Node> items) {
        return new Suite(s.schema(), s.name(), s.description(), s.version(), s.settings(), s.environments(),
                s.authProfiles(), s.variables(), defaults, setup, teardown, items);
    }
}
