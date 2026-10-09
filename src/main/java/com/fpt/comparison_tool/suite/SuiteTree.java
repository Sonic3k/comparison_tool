package com.fpt.comparison_tool.suite;

import com.fpt.comparison_tool.model.Folder;
import com.fpt.comparison_tool.model.Node;
import com.fpt.comparison_tool.model.Suite;
import com.fpt.comparison_tool.model.TestCase;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Walking and rebuilding the folder / test case tree of a suite. */
public final class SuiteTree {

    private SuiteTree() {
    }

    /** A test case with the folders above it, outermost first. */
    public record Located(TestCase tc, List<Folder> chain) {
        public Folder parent() {
            return chain.isEmpty() ? null : chain.get(chain.size() - 1);
        }

        public String path() {
            return String.join("/", chain.stream().map(Folder::name).toList());
        }
    }

    public static List<Located> cases(Suite suite) {
        List<Located> out = new ArrayList<>();
        walk(suite.items(), new ArrayList<>(), out);
        return out;
    }

    private static void walk(List<Node> items, List<Folder> chain, List<Located> out) {
        for (Node n : items) {
            if (n instanceof Folder f) {
                List<Folder> next = new ArrayList<>(chain);
                next.add(f);
                walk(f.items(), next, out);
            } else if (n instanceof TestCase tc) {
                out.add(new Located(tc, List.copyOf(chain)));
            }
        }
    }

    public static void folders(List<Node> items, Consumer<Folder> visitor) {
        for (Node n : items) {
            if (n instanceof Folder f) {
                visitor.accept(f);
                folders(f.items(), visitor);
            }
        }
    }

    /** Gives every folder and test case a unique id (missing or duplicated ids are replaced). */
    public static Suite ensureIds(Suite suite) {
        Set<String> seen = new HashSet<>();
        List<Node> items = ensureIds(suite.items(), seen);
        return new Suite(suite.schema(), suite.name(), suite.description(), suite.version(), suite.settings(),
                suite.environments(), suite.authProfiles(), suite.variables(), suite.defaults(), suite.setup(),
                suite.teardown(), items);
    }

    private static List<Node> ensureIds(List<Node> items, Set<String> seen) {
        List<Node> out = new ArrayList<>(items.size());
        for (Node n : items) {
            if (n instanceof Folder f) {
                String id = f.id();
                if (id.isBlank() || !seen.add(id)) {
                    id = Ids.random("f");
                    seen.add(id);
                }
                out.add(new Folder(id, f.name(), f.description(), f.disabled(), f.defaults(), f.run(), f.setup(),
                        f.teardown(), ensureIds(f.items(), seen)));
            } else if (n instanceof TestCase tc) {
                String id = tc.id();
                if (id.isBlank() || !seen.add(id)) {
                    id = Ids.random("c");
                    seen.add(id);
                }
                out.add(new TestCase(id, tc.key(), tc.name(), tc.description(), tc.tags(), tc.disabled(), tc.dependsOn(),
                        tc.stopOnFailure(), tc.preconditions(), tc.steps(), tc.cleanup()));
            }
        }
        return out;
    }

    public static int countCases(Suite suite) {
        return cases(suite).size();
    }
}
