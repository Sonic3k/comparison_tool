package com.fpt.comparison_tool.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.comparison_tool.engine.Templates;
import com.fpt.comparison_tool.model.AuthProfile;
import com.fpt.comparison_tool.model.Body;
import com.fpt.comparison_tool.model.CompareOptions;
import com.fpt.comparison_tool.model.Defaults;
import com.fpt.comparison_tool.model.Environment;
import com.fpt.comparison_tool.model.Expect;
import com.fpt.comparison_tool.model.Extract;
import com.fpt.comparison_tool.model.Folder;
import com.fpt.comparison_tool.model.KeyValue;
import com.fpt.comparison_tool.model.Node;
import com.fpt.comparison_tool.model.RunPolicy;
import com.fpt.comparison_tool.model.Settings;
import com.fpt.comparison_tool.model.Step;
import com.fpt.comparison_tool.model.StepOptions;
import com.fpt.comparison_tool.model.Suite;
import com.fpt.comparison_tool.model.TestCase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Converts a 1.x suite (testGroups of flat requests with testCaseId / phase) to the folder / test case model while
 * keeping what a 1.x run did:
 * <ul>
 *   <li>"Global Setup" / "Global Teardown" groups become the suite setup / teardown; every other group a folder whose
 *       setup / teardown are the requests of phase setup / teardown, in order.</li>
 *   <li>Requests sharing a testCaseId become one test case, in order of first appearance.</li>
 *   <li>Test cases named SETUP-... / TEARDOWN-... become hooks or preconditions / cleanup where that keeps the 1.x
 *       order: a leading block joins the folder setup, a trailing block the folder teardown, a cleanup case right after
 *       the only case whose data it removes becomes that case's cleanup, a setup case right before the only case that
 *       uses it becomes that case's precondition. Others stay test cases.</li>
 *   <li>A test case that used a variable extracted by another test case gets dependsOn on it, and the producer's
 *       extract is shared with the folder (or the suite, for the suite teardown).</li>
 *   <li>Requests a 1.x run skipped because of the suite verification-mode filter are kept disabled.</li>
 *   <li>Headers repeated on every step of a folder (or of the whole suite) move to its defaults.</li>
 * </ul>
 */
public final class V1Converter {

    /** The converted suite and what the conversion did. */
    public record Result(Suite suite, List<String> notes) {
    }

    private static final Pattern HELPER_SETUP = Pattern.compile("(?i)^setup[-_].*");
    private static final Pattern HELPER_TEARDOWN = Pattern.compile("(?i)^teardown[-_].*");

    private V1Converter() {
    }

    // ---------------------------------------------------------------- working model

    private static final class WStep {
        Step step;
        final int position;
        final Set<String> uses;
        final List<String> extracts;

        WStep(Step step, int position) {
            this.step = step;
            this.position = position;
            this.uses = uses(step);
            this.extracts = step.extract().stream().map(Extract::name).toList();
        }
    }

    private static final class WCase {
        final String key;
        String name;
        String description;
        final List<WStep> steps = new ArrayList<>();
        final List<WStep> preconditions = new ArrayList<>();
        final List<WStep> cleanup = new ArrayList<>();
        final Set<WCase> deps = new LinkedHashSet<>();
        final Map<String, String> exports = new HashMap<>();
        final Map<String, String> movedExports = new HashMap<>();
        boolean moved;
        String movedTo;
        WCase movedInto;

        WCase(String key) {
            this.key = key;
        }

        int first() {
            return steps.isEmpty() ? Integer.MAX_VALUE : steps.get(0).position;
        }

        int last() {
            return steps.isEmpty() ? -1 : steps.get(steps.size() - 1).position;
        }

        boolean helperSetup() {
            return HELPER_SETUP.matcher(key).matches();
        }

        boolean helperTeardown() {
            return HELPER_TEARDOWN.matcher(key).matches();
        }

        boolean helper() {
            return helperSetup() || helperTeardown();
        }

        Set<String> produced() {
            Set<String> out = new LinkedHashSet<>();
            for (WStep s : steps) out.addAll(s.extracts);
            return out;
        }
    }

    private static final class WFolder {
        String name;
        String description;
        boolean disabled;
        final List<WStep> setup = new ArrayList<>();
        final List<WStep> teardown = new ArrayList<>();
        final List<WCase> cases = new ArrayList<>();
        final List<WFolder> subfolders = new ArrayList<>();
    }

    private static final class Notes {
        final List<String> lines = new ArrayList<>();
        final Map<String, Integer> counters = new TreeMap<>();

        void count(String what) {
            counters.merge(what, 1, Integer::sum);
        }

        void add(String line) {
            if (lines.size() < 200) lines.add(line);
        }

        List<String> all() {
            List<String> out = new ArrayList<>();
            counters.forEach((k, v) -> out.add(k + ": " + v));
            out.addAll(lines);
            return out;
        }
    }

    // ---------------------------------------------------------------- conversion

    public static Result convert(JsonNode v1) {
        Notes notes = new Notes();
        JsonNode settings = v1.path("settings");
        JsonNode exec = settings.path("executionConfig");
        String suiteName = text(settings, "suiteName", "Converted suite");
        String mode = text(exec, "mode", "parallel").toLowerCase(Locale.ROOT);
        boolean parallel = mode.equals("parallel");
        String filter = textOrNull(exec, "verificationMode");
        filter = filter == null ? null : filter.toLowerCase(Locale.ROOT);

        List<Environment> envs = new ArrayList<>();
        for (JsonNode e : v1.path("environments")) {
            envs.add(new Environment(text(e, "name", ""), textOrNull(e, "url"), textOrNull(e, "authProfile"),
                    params(e.path("headers")), params(e.path("variables"))));
        }
        List<AuthProfile> profiles = new ArrayList<>();
        for (JsonNode a : v1.path("authProfiles")) {
            String type = text(a, "type", "none");
            if (type.equalsIgnoreCase("saml")) notes.add("Auth profile '" + text(a, "name", "") + "' was SAML (never supported); now type none");
            profiles.add(new AuthProfile(text(a, "name", ""), type, textOrNull(a, "grantType"), textOrNull(a, "tokenUrl"),
                    textOrNull(a, "clientId"), textOrNull(a, "clientSecret"), textOrNull(a, "username"),
                    textOrNull(a, "password"), textOrNull(a, "scope"), textOrNull(a, "token"),
                    textOrNull(a, "refreshToken"), params(a.path("extraParams")), textOrNull(a, "description")));
        }
        List<KeyValue> suiteVars = new ArrayList<>();
        for (JsonNode g : v1.path("globalVariables")) {
            String n = text(g, "name", "");
            if (!n.isBlank()) suiteVars.add(KeyValue.of(n, text(g, "value", "")));
        }
        if (!suiteVars.isEmpty()) notes.add("1.x session variables kept as suite variables: " + suiteVars.stream().map(KeyValue::key).toList());

        JsonNode cmp = settings.path("comparisonConfig");
        CompareOptions compare = cmp.isMissingNode() || cmp.isNull() ? null : new CompareOptions(
                split(text(cmp, "ignoreFieldsRaw", "")), cmp.path("ignoreArrayOrder").asBoolean(false),
                cmp.has("caseSensitive") ? cmp.path("caseSensitive").asBoolean(true) : null,
                cmp.path("numericTolerance").asDouble(0), cmp.path("compareErrorResponses").asBoolean(false));
        String target = textOrNull(exec, "targetEnvironment");
        String baseline = "comparison".equals(filter) || "both".equals(filter) ? textOrNull(exec, "sourceEnvironment") : null;
        Settings newSettings = new Settings(parallel ? 8 : null, target, baseline,
                new RunPolicy(parallel ? RunPolicy.PARALLEL : RunPolicy.SERIAL, RunPolicy.SERIAL), compare);
        int timeout = exec.path("timeout").asInt(0);

        // groups
        List<WStep> suiteSetup = new ArrayList<>();
        List<WStep> suiteTeardown = new ArrayList<>();
        List<WFolder> folders = new ArrayList<>();
        int[] position = {0};
        for (JsonNode g : v1.path("testGroups")) {
            String gname = text(g, "name", "Group");
            JsonNode requests = g.has("testRequests") ? g.path("testRequests") : g.path("testCases");
            if (gname.startsWith("Global Setup") || gname.startsWith("Global Teardown")) {
                List<WStep> target2 = gname.startsWith("Global Setup") ? suiteSetup : suiteTeardown;
                for (JsonNode r : requests) {
                    target2.add(new WStep(step(r, null, notes, true), position[0]++));
                    notes.count("suite " + (gname.startsWith("Global Setup") ? "setup" : "teardown") + " steps");
                }
                continue;
            }
            WFolder f = new WFolder();
            f.name = gname;
            f.description = textOrNull(g, "description");
            f.disabled = g.has("enabled") && !g.path("enabled").asBoolean(true);
            Map<String, JsonNode> defs = new HashMap<>();
            for (JsonNode d : g.path("testCaseDefs")) defs.put(text(d, "id", ""), d);
            Map<String, WCase> byKey = new LinkedHashMap<>();
            for (JsonNode r : requests) {
                String phase = text(r, "phase", "test").toLowerCase(Locale.ROOT);
                if (phase.equals("setup")) {
                    f.setup.add(new WStep(step(r, null, notes, true), position[0]++));
                } else if (phase.equals("teardown")) {
                    f.teardown.add(new WStep(step(r, null, notes, true), position[0]++));
                } else {
                    String key = text(r, "testCaseId", text(r, "id", "TC"));
                    WCase c = byKey.computeIfAbsent(key, WCase::new);
                    c.steps.add(new WStep(step(r, filter, notes, false), position[0]++));
                }
            }
            for (WCase c : byKey.values()) {
                JsonNode d = defs.get(c.key);
                c.name = d != null ? text(d, "name", c.key) : c.key;
                c.description = d != null ? textOrNull(d, "description") : null;
                if (c.name.equals(c.key) && c.steps.size() == 1 && !c.steps.get(0).step.name().isBlank()) {
                    c.name = c.steps.get(0).step.name();
                }
            }
            f.cases.addAll(byKey.values());
            folders.add(f);
        }

        // variables available without any test case
        Set<String> envVars = new HashSet<>(Set.of("baseUrl"));
        for (Environment e : envs) for (KeyValue v : e.variables()) envVars.add(bare(v.key()));
        for (KeyValue v : suiteVars) envVars.add(v.key());
        Set<String> suiteSetupVars = new HashSet<>();
        for (WStep s : suiteSetup) suiteSetupVars.addAll(s.extracts);

        for (WFolder f : folders) analyseFolder(f, envVars, suiteSetupVars, notes);
        suiteTeardownExports(folders, suiteTeardown, envVars, suiteSetupVars, notes);

        // build the model
        List<Node> items = new ArrayList<>();
        for (WFolder f : folders) items.add(buildFolder(suiteName, f, parallel));
        Suite suite = new Suite(null, suiteName, textOrNull(settings, "description"), textOrNull(settings, "version"),
                newSettings, envs, profiles, suiteVars, timeout > 0 ? new Defaults(null, null, timeout) : null,
                plain(suiteSetup), plain(suiteTeardown), items);
        suite = HeaderHoist.apply(suite, notes.counters);
        notes.counters.put("folders", folders.size());
        notes.counters.put("test cases", SuiteTree.countCases(suite));
        return new Result(SuiteTree.ensureIds(suite), notes.all());
    }

    // ---------------------------------------------------------------- variables and helper cases

    private static final class Deps {
        final Map<String, List<WCase>> producerCases = new HashMap<>();
        final Map<WCase, Map<WCase, Set<String>>> depVars = new LinkedHashMap<>();
        final Map<WCase, Set<WCase>> consumers = new HashMap<>();
    }

    private static void analyseFolder(WFolder f, Set<String> envVars, Set<String> suiteSetupVars, Notes notes) {
        inlineInterleaved(f, notes);
        Set<String> base = new HashSet<>(envVars);
        base.addAll(suiteSetupVars);
        Set<String> available = new HashSet<>(base);
        for (WStep s : f.setup) available.addAll(s.extracts);
        Deps deps = dependencies(f, available, notes);

        // sections: a group made of several screens, each opened by a block of shared SETUP cases, becomes sub-folders
        List<Integer> starts = sectionStarts(f.cases, deps);
        boolean split = starts.size() >= 2 || (starts.size() == 1 && starts.get(0) > 0);
        if (split) {
            Map<WCase, Integer> section = new HashMap<>();
            for (int i = 0; i < f.cases.size(); i++) {
                int sec = -1;
                for (int k = 0; k < starts.size(); k++) if (i >= starts.get(k)) sec = k;
                section.put(f.cases.get(i), sec);
            }
            for (Map.Entry<WCase, Map<WCase, Set<String>>> e : deps.depVars.entrySet()) {
                for (WCase p : e.getValue().keySet()) {
                    int sp = section.get(p);
                    if (sp != -1 && sp != section.get(e.getKey())) {
                        if (split) notes.add(f.name + ": kept as one folder - " + e.getKey().key + " uses " + e.getValue().get(p)
                                + " from " + p.key + " of another screen section");
                        split = false;
                    }
                }
            }
            if (split) {
                List<WCase> all = new ArrayList<>(f.cases);
                f.cases.clear();
                f.cases.addAll(all.subList(0, starts.get(0)));
                for (int k = 0; k < starts.size(); k++) {
                    WFolder sub = new WFolder();
                    int to = k + 1 < starts.size() ? starts.get(k + 1) : all.size();
                    sub.cases.addAll(all.subList(starts.get(k), to));
                    sub.name = sectionName(sub.cases.get(0).key, k + 1);
                    f.subfolders.add(sub);
                }
                notes.count("sub-folders created from screen sections");
            }
        }
        if (!f.subfolders.isEmpty()) {
            // parent cases run before the sub-folders, so their shared values reach the sub-folders through the folder
            Set<WCase> parentCases = new HashSet<>(f.cases);
            for (Map.Entry<WCase, Map<WCase, Set<String>>> e : deps.depVars.entrySet()) {
                if (parentCases.contains(e.getKey())) continue;
                for (Map.Entry<WCase, Set<String>> d : e.getValue().entrySet()) {
                    if (parentCases.contains(d.getKey())) {
                        for (String v : d.getValue()) d.getKey().exports.put(v, Extract.FOLDER);
                    }
                }
            }
        }
        place(f, deps, available, notes);
        for (WFolder sub : f.subfolders) {
            Set<String> subAvailable = new HashSet<>(available);
            place(sub, deps, subAvailable, notes);
        }
        if (!f.subfolders.isEmpty()) parentTeardownExports(f, available, notes);
    }

    /**
     * 1.x ran requests in file order, so a SETUP/TEARDOWN case listed between two requests of one test case ran in the
     * middle of it (for example create, delete-all, then search expecting nothing). Such helpers become steps of that
     * test case, in their original place.
     */
    private static void inlineInterleaved(WFolder f, Notes notes) {
        List<WCase> real = f.cases.stream().filter(c -> !c.helper()).toList();
        for (java.util.Iterator<WCase> it = f.cases.iterator(); it.hasNext(); ) {
            WCase h = it.next();
            if (!h.helper() || h.steps.isEmpty()) continue;
            int a = h.first();
            int b = h.last();
            WCase host = null;
            for (WCase c : real) {
                if (c.first() < a && c.last() > b && (host == null || c.last() - c.first() < host.last() - host.first())) host = c;
            }
            if (host == null) continue;
            host.steps.addAll(h.steps);
            host.steps.sort(java.util.Comparator.comparingInt(x -> x.position));
            it.remove();
            notes.count("SETUP/TEARDOWN test cases kept as steps of the test case they ran in the middle of");
        }
    }

    /**
     * The teardown of a folder that was split into screen sub-folders reads values the sub-folders produce. A value saved
     * with folder scope stays in its sub-folder, so these are saved for the whole run instead (1.x kept them global).
     */
    private static void parentTeardownExports(WFolder f, Set<String> available, Notes notes) {
        Set<String> uses = new LinkedHashSet<>();
        for (WStep s : f.teardown) uses.addAll(s.uses);
        uses.removeAll(available);
        for (WCase c : f.cases) for (WStep s : c.steps) uses.removeAll(s.extracts);
        for (String v : uses) {
            boolean found = false;
            for (WFolder sub : f.subfolders) {
                for (WCase c : sub.cases) {
                    for (WStep s : c.steps) {
                        if (s.extracts.contains(v)) {
                            c.exports.put(v, Extract.SUITE);
                            found = true;
                        }
                    }
                    for (WStep s : c.preconditions) {
                        if (s.extracts.contains(v)) {
                            c.movedExports.put(v, Extract.SUITE);
                            found = true;
                        }
                    }
                    for (WStep s : c.cleanup) {
                        if (s.extracts.contains(v)) {
                            c.movedExports.put(v, Extract.SUITE);
                            found = true;
                        }
                    }
                }
                for (WStep s : sub.setup) {
                    if (s.extracts.contains(v)) {
                        s.step = withExtractScope(s.step, v, Extract.SUITE);
                        found = true;
                    }
                }
            }
            if (found) notes.count("values shared with the teardown of the parent folder");
        }
    }

    private static Deps dependencies(WFolder f, Set<String> available, Notes notes) {
        Deps d = new Deps();
        Map<WCase, Map<String, List<Integer>>> extractPositions = new HashMap<>();
        for (WCase c : f.cases) {
            Map<String, List<Integer>> pos = new HashMap<>();
            for (WStep s : c.steps) {
                for (String v : s.extracts) {
                    pos.computeIfAbsent(v, k -> new ArrayList<>()).add(s.position);
                    d.producerCases.computeIfAbsent(v, k -> new ArrayList<>()).add(c);
                }
            }
            extractPositions.put(c, pos);
        }
        for (WCase c : f.cases) {
            Set<String> own = new HashSet<>();
            for (WStep s : c.steps) {
                for (String v : s.uses) {
                    if (own.contains(v) || available.contains(v) || v.startsWith("$")) continue;
                    WCase best = null;
                    int bestPos = -1;
                    for (WCase p : d.producerCases.getOrDefault(v, List.of())) {
                        if (p == c) continue;
                        for (int pos : extractPositions.get(p).getOrDefault(v, List.of())) {
                            if (pos < s.position && pos > bestPos) {
                                bestPos = pos;
                                best = p;
                            }
                        }
                    }
                    if (best != null) {
                        d.depVars.computeIfAbsent(c, k -> new LinkedHashMap<>()).computeIfAbsent(best, k -> new LinkedHashSet<>()).add(v);
                    } else if (d.producerCases.containsKey(v)) {
                        notes.add(f.name + " / " + c.key + ": uses {{" + v + "}} before any test case extracts it (1.x read a value left by an earlier run)");
                    } else {
                        notes.add(f.name + " / " + c.key + ": uses {{" + v + "}}, which nothing in the suite sets");
                    }
                }
                own.addAll(s.extracts);
            }
        }
        for (Map.Entry<WCase, Map<WCase, Set<String>>> e : d.depVars.entrySet()) {
            for (WCase producer : e.getValue().keySet()) d.consumers.computeIfAbsent(producer, k -> new LinkedHashSet<>()).add(e.getKey());
        }
        return d;
    }

    /** Indexes where a block of SETUP cases shared by several later test cases begins. */
    private static List<Integer> sectionStarts(List<WCase> order, Deps deps) {
        List<Integer> starts = new ArrayList<>();
        int i = 0;
        while (i < order.size()) {
            if (!order.get(i).helper()) {
                i++;
                continue;
            }
            int j = i;
            while (j < order.size() && order.get(j).helper()) j++;
            Set<WCase> block = new HashSet<>(order.subList(i, j));
            WCase after = j < order.size() ? order.get(j) : null;
            boolean shared = false;
            for (WCase h : block) {
                if (!h.helperSetup()) continue;
                for (WCase c : deps.consumers.getOrDefault(h, Set.of())) {
                    if (!block.contains(c) && c != after && !c.helper()) shared = true;
                }
            }
            if (shared && after != null) {
                int first = i;
                while (first < j && !order.get(first).helperSetup()) first++;
                starts.add(first);
            }
            i = j;
        }
        return starts;
    }

    private static String sectionName(String key, int n) {
        String k = key.replaceFirst("(?i)^setup[-_]", "");
        int cut = k.toUpperCase(Locale.ROOT).indexOf("-SETUP");
        if (cut < 0) cut = k.toUpperCase(Locale.ROOT).indexOf("-A-");
        if (cut > 0) k = k.substring(0, cut);
        String[] parts = k.split("[-_]");
        int from = parts.length > 1 && parts[0].length() <= 3 && parts[0].equals(parts[0].toUpperCase(Locale.ROOT)) ? 1 : 0;
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(p.length() <= 3 ? p.toUpperCase(Locale.ROOT) : p.substring(0, 1).toUpperCase(Locale.ROOT) + p.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.isEmpty() ? "Section " + n : String.format("%02d %s", n, sb);
    }

    /** Turns the helper cases of one folder into hooks, preconditions or cleanup, then adds dependsOn and exports. */
    private static void place(WFolder f, Deps deps, Set<String> available, Notes notes) {
        List<WCase> order = new ArrayList<>(f.cases);
        Set<WCase> members = new HashSet<>(order);
        Map<WCase, Set<WCase>> consumers = deps.consumers;
        int firstReal = 0;
        while (firstReal < order.size() && order.get(firstReal).helper()) firstReal++;
        int lastReal = order.size() - 1;
        while (lastReal >= 0 && order.get(lastReal).helper()) lastReal--;

        if (lastReal < 0) {
            firstReal = 0;
            lastReal = order.size() - 1;
        } else {
            for (int i = 0; i < firstReal; i++) {
                WCase h = order.get(i);
                h.moved = true;
                h.movedTo = "setup";
                f.setup.addAll(h.steps);
                for (WStep st : h.steps) available.addAll(st.extracts);
                notes.count("SETUP/TEARDOWN test cases moved to a folder setup");
            }
            List<WStep> tail = new ArrayList<>();
            for (int i = lastReal + 1; i < order.size(); i++) {
                WCase h = order.get(i);
                h.moved = true;
                h.movedTo = "teardown";
                tail.addAll(h.steps);
                notes.count("SETUP/TEARDOWN test cases moved to a folder teardown");
            }
            f.teardown.addAll(0, tail);
        }

        int i = firstReal;
        while (i <= lastReal) {
            if (!order.get(i).helper()) {
                i++;
                continue;
            }
            int j = i;
            while (j <= lastReal && order.get(j).helper()) j++;
            List<WCase> block = order.subList(i, j);
            WCase before = order.get(i - 1);
            WCase after = order.get(j);
            Set<WCase> inBlock = new HashSet<>(block);
            boolean keep = false;
            for (WCase h : block) {
                for (WCase c : consumers.getOrDefault(h, Set.of())) {
                    if (!inBlock.contains(c) && c != after && !c.helper()) keep = true;
                }
                if (consumers.getOrDefault(h, Set.of()).contains(after) && h.helperTeardown()) keep = true;
            }
            if (!keep) {
                int split = 0;
                while (split < block.size() && block.get(split).helperTeardown()) split++;
                for (int k = 0; k < block.size(); k++) {
                    WCase h = block.get(k);
                    h.moved = true;
                    if (k < split) {
                        h.movedTo = "cleanup";
                        h.movedInto = before;
                        before.cleanup.addAll(h.steps);
                        notes.count("SETUP/TEARDOWN test cases moved to the cleanup of the test case before them");
                    } else {
                        h.movedTo = "precondition";
                        h.movedInto = after;
                        after.preconditions.addAll(h.steps);
                        notes.count("SETUP/TEARDOWN test cases moved to the preconditions of the test case after them");
                    }
                }
            }
            i = j;
        }

        for (Map.Entry<WCase, Map<WCase, Set<String>>> e : deps.depVars.entrySet()) {
            WCase consumer = e.getKey();
            if (!members.contains(consumer)) continue;
            WCase runsIn = consumer;
            if (consumer.moved) {
                if ("cleanup".equals(consumer.movedTo) || "precondition".equals(consumer.movedTo)) runsIn = consumer.movedInto;
                else if ("teardown".equals(consumer.movedTo)) {
                    for (Map.Entry<WCase, Set<String>> d : e.getValue().entrySet()) {
                        WCase p = d.getKey();
                        for (String v : d.getValue()) {
                            if (!p.moved) p.exports.put(v, Extract.FOLDER);
                            else if (p.movedInto != null) p.movedInto.movedExports.put(v, Extract.FOLDER);
                        }
                    }
                    continue;
                } else {
                    continue;
                }
            }
            for (Map.Entry<WCase, Set<String>> d : e.getValue().entrySet()) {
                WCase producer = d.getKey();
                if (!members.contains(producer)) continue;
                Set<String> vars = d.getValue();
                WCase producedIn = producer;
                if (producer.moved) {
                    if ("setup".equals(producer.movedTo)) continue;
                    if ("teardown".equals(producer.movedTo)) {
                        notes.add(f.name + " / " + consumer.key + ": uses " + vars + " set by " + producer.key + ", which runs in the folder teardown");
                        continue;
                    }
                    producedIn = producer.movedInto;
                }
                if (producedIn == runsIn) continue;
                for (String v : vars) {
                    if (producer.moved) producedIn.movedExports.put(v, Extract.FOLDER);
                    else producer.exports.put(v, Extract.FOLDER);
                }
                if (runsIn.deps.add(producedIn)) notes.count("dependsOn added");
            }
        }

        Set<String> teardownUses = new LinkedHashSet<>();
        for (WStep s : f.teardown) teardownUses.addAll(s.uses);
        for (String v : teardownUses) {
            if (available.contains(v)) continue;
            for (WCase p : deps.producerCases.getOrDefault(v, List.of())) {
                if (!members.contains(p)) continue;
                if (!p.moved) p.exports.put(v, Extract.FOLDER);
                else if (p.movedInto != null) p.movedInto.movedExports.put(v, Extract.FOLDER);
            }
        }
        f.cases.removeIf(c -> c.moved);
    }

    private static void suiteTeardownExports(List<WFolder> folders, List<WStep> suiteTeardown, Set<String> envVars,
                                             Set<String> suiteSetupVars, Notes notes) {
        Set<String> uses = new LinkedHashSet<>();
        for (WStep s : suiteTeardown) uses.addAll(s.uses);
        uses.removeAll(envVars);
        uses.removeAll(suiteSetupVars);
        for (String v : uses) {
            boolean found = false;
            List<WFolder> allFolders = new ArrayList<>();
            for (WFolder f : folders) {
                allFolders.add(f);
                allFolders.addAll(f.subfolders);
            }
            for (WFolder f : allFolders) {
                for (WCase c : f.cases) {
                    for (WStep s : c.steps) {
                        if (s.extracts.contains(v)) {
                            c.exports.put(v, Extract.SUITE);
                            found = true;
                        }
                    }
                    for (WStep s : c.cleanup) if (s.extracts.contains(v)) found = true;
                }
                for (WStep s : f.setup) {
                    if (s.extracts.contains(v)) {
                        s.step = withExtractScope(s.step, v, Extract.SUITE);
                        found = true;
                    }
                }
            }
            if (found) notes.count("variables shared with the suite teardown");
            else if (!v.startsWith("$")) notes.add("Suite teardown uses {{" + v + "}}, which no step extracts");
        }
    }

    private static boolean onlyHelpersBetween(List<WCase> order, WCase a, WCase b) {
        int ia = order.indexOf(a);
        int ib = order.indexOf(b);
        if (ia < 0 || ib < 0 || ia >= ib) return false;
        for (int i = ia + 1; i < ib; i++) {
            WCase x = order.get(i);
            if (!(x.helperSetup() || x.helperTeardown())) return false;
        }
        return true;
    }

    /** True when a test case other than TEARDOWN cases reads variables the producer extracted. */
    private static boolean otherConsumers(Map<WCase, Map<WCase, Set<String>>> depVars, WCase producer) {
        for (Map.Entry<WCase, Map<WCase, Set<String>>> e : depVars.entrySet()) {
            if (!e.getKey().helperTeardown() && e.getValue().containsKey(producer)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- model building

    private static Folder buildFolder(String suiteName, WFolder f, boolean parallel) {
        return buildFolder(suiteName, f, f.name);
    }

    private static Folder buildFolder(String suiteName, WFolder f, String idPath) {
        List<Node> items = new ArrayList<>();
        for (WCase c : f.cases) {
            List<Step> steps = withExports(plain(c.steps), c.exports);
            List<Step> pre = withExports(plain(c.preconditions), c.movedExports);
            List<Step> cleanup = withExports(plain(c.cleanup), c.movedExports);
            boolean allDisabled = !steps.isEmpty() && steps.stream().noneMatch(Step::active);
            if (allDisabled) steps = steps.stream().map(V1Converter::enable).toList();
            items.add(new TestCase(Ids.stable("c", suiteName + "/" + idPath + "/" + c.key), c.key, c.name, c.description,
                    null, allDisabled ? Boolean.TRUE : null, c.deps.stream().map(d -> d.key).toList(), null,
                    pre, steps, cleanup));
        }
        for (WFolder sub : f.subfolders) items.add(buildFolder(suiteName, sub, idPath + "/" + sub.name));
        return new Folder(Ids.stable("f", suiteName + "/" + idPath), f.name, f.description, f.disabled ? Boolean.TRUE : null,
                null, null, plain(f.setup), plain(f.teardown), items);
    }

    private static List<Step> withExports(List<Step> steps, Map<String, String> exports) {
        if (exports.isEmpty()) return steps;
        List<Step> out = new ArrayList<>();
        for (Step s : steps) {
            Step x = s;
            for (Map.Entry<String, String> e : exports.entrySet()) x = withExtractScope(x, e.getKey(), e.getValue());
            out.add(x);
        }
        return out;
    }

    private static Step withExtractScope(Step s, String var, String scope) {
        if (s.extract().stream().noneMatch(e -> e.name().equals(var))) return s;
        List<Extract> ex = s.extract().stream()
                .map(e -> e.name().equals(var) && !Extract.SUITE.equals(e.scope()) ? e.withScope(scope) : e).toList();
        return new Step(s.name(), s.disabled(), s.method(), s.url(), s.query(), s.headers(), s.body(), s.auth(), ex,
                s.expect(), s.options(), s.notes());
    }

    private static Step enable(Step s) {
        return new Step(s.name(), null, s.method(), s.url(), s.query(), s.headers(), s.body(), s.auth(), s.extract(),
                s.expect(), s.options(), s.notes());
    }

    private static List<Step> plain(List<WStep> steps) {
        return steps.stream().map(w -> w.step).toList();
    }

    /** Converts one 1.x request. hook = true for setup / teardown requests (never filtered by verification mode). */
    private static Step step(JsonNode r, String filter, Notes notes, boolean hook) {
        String method = text(r, "method", "GET");
        String endpoint = text(r, "endpoint", "");
        String name = text(r, "name", "");
        if (name.isBlank()) name = method + " " + endpoint;
        boolean disabled = r.has("enabled") && !r.path("enabled").asBoolean(true);
        String mode = text(r, "verificationMode", "comparison").toLowerCase(Locale.ROOT);
        if (!hook && filter != null && skippedByFilter(mode, filter)) {
            if (!disabled) notes.count("steps disabled because 1.x runs skipped them (verification mode " + mode + ")");
            disabled = true;
        }

        List<KeyValue> headers = new ArrayList<>();
        String rawHeaders = text(r, "headers", "");
        for (String line : rawHeaders.split("\n")) {
            int idx = line.indexOf(':');
            if (idx > 0) headers.add(KeyValue.of(line.substring(0, idx).trim(), line.substring(idx + 1).trim()));
        }

        Body body = null;
        List<KeyValue> form = params(r.path("formParams"));
        String json = text(r, "jsonBody", "");
        if (!form.isEmpty()) {
            body = new Body(Body.FORM, null, null, form);
        } else if (!json.isBlank()) {
            JsonNode parsed = null;
            try {
                parsed = Json.MAPPER.readTree(json);
            } catch (Exception ignored) {
                // keep as text
            }
            body = parsed != null && (parsed.isObject() || parsed.isArray())
                    ? new Body(Body.JSON, parsed, null, null) : new Body(Body.JSON, null, json, null);
        }

        List<Extract> extracts = new ArrayList<>();
        for (String entry : text(r, "extractVariables", "").split("\\r?\\n|,")) {
            String e = entry.trim();
            int eq = e.indexOf('=');
            if (eq > 0) extracts.add(new Extract(bare(e.substring(0, eq).trim()), e.substring(eq + 1).trim(), null));
        }

        Expect expect = null;
        JsonNode auto = r.path("automationConfig");
        if (mode.equals("automation") || mode.equals("both")) {
            String status = textOrNull(auto, "expectedStatus");
            List<String> lines = new ArrayList<>();
            for (String l : text(auto, "expectedBody", "").split("\\r?\\n")) if (!l.isBlank()) lines.add(l.trim());
            expect = new Expect(status, lines, null);
            if (!text(auto, "expectedHeaders", "").isBlank()) notes.count("expectedHeaders dropped (1.x never checked them)");
            if (auto.path("maxResponseTime").asInt(0) > 0) notes.count("maxResponseTime dropped (1.x never checked it)");
        } else if (!auto.isMissingNode() && !text(auto, "expectedBody", "").isBlank()) {
            notes.count("assertions dropped on steps of mode " + mode + " (1.x did not evaluate them)");
        }

        StepOptions options = null;
        int delay = r.path("delayMs").asInt(0);
        JsonNode cc = r.path("comparisonConfig");
        List<String> ignore = cc.isMissingNode() || cc.isNull() ? List.of() : split(text(cc, "ignoreFieldsRaw", ""));
        boolean compareOff = mode.equals("automation") || mode.equals("none");
        if (delay > 0 || !ignore.isEmpty() || compareOff) {
            options = new StepOptions(delay > 0 ? delay : null, null, null, compareOff ? Boolean.FALSE : null, ignore, null);
        }

        return new Step(name, disabled ? Boolean.TRUE : null, method, endpoint, params(r.path("queryParams")), headers,
                body, textOrNull(r, "authProfile"), extracts, expect, options, textOrNull(r, "description"));
    }

    private static boolean skippedByFilter(String mode, String filter) {
        return switch (filter) {
            case "comparison" -> mode.equals("automation") || mode.equals("none");
            case "automation" -> mode.equals("comparison") || mode.equals("none");
            case "both" -> !mode.equals("both");
            case "none" -> !mode.equals("none");
            default -> false;
        };
    }

    private static Set<String> uses(Step s) {
        Set<String> out = new LinkedHashSet<>();
        out.addAll(Templates.names(s.url()));
        s.query().forEach(q -> {
            out.addAll(Templates.names(q.key()));
            out.addAll(Templates.names(q.value()));
        });
        s.headers().forEach(h -> out.addAll(Templates.names(h.value())));
        if (s.body() != null) {
            if (s.body().json() != null) out.addAll(Templates.names(s.body().json().toString()));
            out.addAll(Templates.names(s.body().raw()));
            s.body().form().forEach(f -> out.addAll(Templates.names(f.value())));
        }
        Expect e = s.expect();
        if (e != null) {
            out.addAll(Templates.names(e.status()));
            e.assertions().forEach(a -> out.addAll(Templates.names(a)));
        }
        return out;
    }

    // ---------------------------------------------------------------- json helpers

    private static List<KeyValue> params(JsonNode arr) {
        List<KeyValue> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode p : arr) {
            String k = text(p, "key", text(p, "name", ""));
            if (!k.isBlank()) out.add(KeyValue.of(k, text(p, "value", "")));
        }
        return out;
    }

    private static List<String> split(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String s : raw.split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    private static String text(JsonNode n, String field, String def) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? def : v.asText(def);
    }

    private static String textOrNull(JsonNode n, String field) {
        String v = text(n, field, null);
        return v == null || v.isBlank() ? null : v;
    }

    private static String bare(String name) {
        String n = name.trim();
        if (n.startsWith("{{") && n.endsWith("}}")) n = n.substring(2, n.length() - 2);
        return n.trim();
    }

    static boolean same(Object a, Object b) {
        return Objects.equals(a, b);
    }
}
