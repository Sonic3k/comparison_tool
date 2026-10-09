package com.fpt.comparison_tool.engine;

import com.fpt.comparison_tool.model.Suite;
import com.fpt.comparison_tool.model.KeyValue;
import com.fpt.comparison_tool.result.CaseResult;
import com.fpt.comparison_tool.result.DisabledCase;
import com.fpt.comparison_tool.result.HookResult;
import com.fpt.comparison_tool.result.RunHeader;
import com.fpt.comparison_tool.result.RunSummary;
import com.fpt.comparison_tool.result.Status;
import com.fpt.comparison_tool.result.StepResult;
import com.fpt.comparison_tool.result.VarUse;
import com.fpt.comparison_tool.suite.SuiteJson;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunEngineTest {

    static FakeApi target;
    static FakeApi baseline;
    static RunEngine engine;

    @BeforeAll
    static void start() throws Exception {
        target = new FakeApi("target");
        baseline = new FakeApi("baseline");
        engine = new RunEngine();
    }

    @AfterAll
    static void stop() throws Exception {
        target.close();
        baseline.close();
        engine.close();
    }

    static final class Collector implements RunListener {
        final List<CaseResult> cases = Collections.synchronizedList(new ArrayList<>());
        final List<HookResult> hooks = Collections.synchronizedList(new ArrayList<>());
        final List<String> order = Collections.synchronizedList(new ArrayList<>());
        RunHeader header;

        @Override
        public void started(RunHeader h) {
            header = h;
        }

        @Override
        public void hook(HookResult r) {
            hooks.add(r);
            order.add(r.hook() + ":" + String.join("/", r.path()));
        }

        @Override
        public void caseFinished(CaseResult r) {
            cases.add(r);
            order.add("case:" + r.key());
        }

        CaseResult get(String key) {
            return cases.stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
        }
    }

    static Suite suite(String itemsJson, String extra) {
        String json = """
                {
                  "name": "t",
                  "settings": {"concurrency": 8},
                  "environments": [
                    {"name": "target", "baseUrl": "%s", "variables": [{"key": "who", "value": "T"}]},
                    {"name": "base", "baseUrl": "%s", "variables": [{"key": "who", "value": "B"}]}
                  ],
                  %s
                  "items": %s
                }
                """.formatted(target.url(), baseline.url(), extra == null ? "" : extra + ",", itemsJson);
        return SuiteJson.parse(json).suite();
    }

    static RunRequest request(Suite s) {
        return new RunRequest(s, "target", null, Selection.all(), new RunOptions(null, "all", null, null, null, null), null);
    }

    @Test
    void parallelCasesKeepTheirOwnVariables() {
        StringBuilder cases = new StringBuilder();
        for (int i = 0; i < 24; i++) {
            if (i > 0) cases.append(',');
            cases.append("""
                    {"type": "case", "key": "C%d", "name": "chain %d", "steps": [
                      {"name": "create", "method": "POST", "url": "/items", "body": {"type": "json", "json": {"name": "n%d"}},
                       "extract": [{"name": "id", "path": "$.id"}], "expect": {"status": "201"}},
                      {"name": "slow", "url": "/slow"},
                      {"name": "read", "url": "/items/{{id}}", "expect": {"status": "200", "assertions": ["$.name == \\"n%d\\""]}}
                    ]}""".formatted(i, i, i, i));
        }
        Suite s = suite("[{\"type\": \"folder\", \"name\": \"F\", \"items\": [" + cases + "]}]", null);
        Collector c = new Collector();
        RunSummary sum = engine.run(request(s), c);
        assertEquals(24, sum.total());
        assertEquals(24, sum.count(Status.PASS), () -> c.cases.stream().filter(x -> x.statusEnum() != Status.PASS)
                .map(x -> x.key() + " " + x.reason()).collect(Collectors.joining("\n")));
        assertTrue(target.maxConcurrent.get() > 1, "test cases should overlap");
    }

    @Test
    void folderSetupVariablesReachCasesAndTeardownRunsLast() {
        String items = """
                [{"type": "folder", "name": "F",
                  "setup": [{"name": "seed", "method": "POST", "url": "/items", "body": {"type": "json", "json": {"name": "shared"}},
                             "extract": [{"name": "sid", "path": "$.id"}]}],
                  "teardown": [{"name": "remove", "method": "DELETE", "url": "/items/{{sid}}"}],
                  "items": [
                    {"type": "case", "key": "A", "steps": [{"url": "/items/{{sid}}", "expect": {"assertions": ["$.name == shared"]}}]},
                    {"type": "case", "key": "B", "steps": [{"url": "/items/{{sid}}", "expect": {"assertions": ["$.id exists"]}}]}
                  ]}]""";
        Collector c = new Collector();
        RunSummary sum = engine.run(request(suite(items, null)), c);
        assertEquals(2, sum.count(Status.PASS));
        assertEquals("setup:F", c.order.get(0));
        assertEquals("teardown:F", c.order.get(c.order.size() - 1));
        HookResult setup = c.hooks.get(0);
        assertNotNull(setup.vars().get("target").get("sid"));
    }

    @Test
    void requiredSetupFailureBlocksTheFolder() {
        String items = """
                [{"type": "folder", "name": "F",
                  "setup": [{"name": "must", "url": "/nothing-here", "options": {"required": true}}],
                  "items": [{"type": "case", "key": "A", "steps": [{"url": "/slow"}]}]}]""";
        Collector c = new Collector();
        engine.run(request(suite(items, null)), c);
        assertEquals(Status.BLOCKED, c.get("A").statusEnum());
        assertTrue(c.get("A").reason().contains("required step"));
    }

    @Test
    void dependsOnWaitsAndSharesFolderExports() {
        String items = """
                [{"type": "folder", "name": "F", "items": [
                  {"type": "case", "key": "B", "dependsOn": ["A"], "steps": [
                    {"url": "/items/{{made}}", "expect": {"assertions": ["$.name == from-a"]}}]},
                  {"type": "case", "key": "A", "steps": [
                    {"url": "/slow"},
                    {"method": "POST", "url": "/items", "body": {"type": "json", "json": {"name": "from-a"}},
                     "extract": [{"name": "made", "path": "$.id", "scope": "folder"}]}]}
                ]}]""";
        Collector c = new Collector();
        RunSummary sum = engine.run(request(suite(items, null)), c);
        assertEquals(2, sum.count(Status.PASS), () -> c.cases.toString());
        assertTrue(c.order.indexOf("case:A") < c.order.indexOf("case:B"));
    }

    @Test
    void caseVariablesAreNotVisibleToOtherCases() {
        String items = """
                [{"type": "folder", "name": "F", "run": {"cases": "serial"}, "items": [
                  {"type": "case", "key": "A", "steps": [{"method": "POST", "url": "/items", "body": {"type": "json", "json": {"name": "x"}},
                                                         "extract": [{"name": "own", "path": "$.id"}]}]},
                  {"type": "case", "key": "B", "steps": [{"url": "/items/{{own}}"}]}
                ]}]""";
        Collector c = new Collector();
        engine.run(request(suite(items, null)), c);
        assertEquals(Status.PASS, c.get("A").statusEnum());
        assertEquals(Status.ERROR, c.get("B").statusEnum());
        assertTrue(c.get("B").reason().contains("{{own}}"));
    }

    @Test
    void retryRepeatsUntilThePassAndErrorSkipsTheRest() {
        target.flakyCalls.set(0);
        String items = """
                [{"type": "case", "key": "R", "steps": [
                   {"url": "/flaky", "expect": {"status": "200"}, "options": {"retry": {"times": 3, "intervalMs": 10}}}]},
                 {"type": "case", "key": "E", "steps": [
                   {"url": "/items/{{missing}}"}, {"url": "/slow"}],
                  "cleanup": [{"url": "/slow"}]}]""";
        Collector c = new Collector();
        engine.run(request(suite(items, null)), c);
        CaseResult r = c.get("R");
        assertEquals(Status.PASS, r.statusEnum());
        assertEquals(3, r.steps().get(0).attempts());
        CaseResult e = c.get("E");
        assertEquals(Status.ERROR, e.statusEnum());
        assertEquals("skipped", e.steps().get(1).status());
        assertEquals("pass", e.steps().get(2).status());
        assertEquals("cleanup", e.steps().get(2).phase());
    }

    @Test
    void compareRunDiffsEachStepWithItsOwnVariables() {
        String items = """
                [{"type": "case", "key": "Q", "steps": [
                   {"url": "/quote", "extract": [{"name": "p", "path": "$.price"}], "options": {"ignoreFields": ["ts"]}}]},
                 {"type": "case", "key": "W", "steps": [
                   {"method": "POST", "url": "/items", "body": {"type": "json", "json": {"name": "{{who}}"}},
                    "extract": [{"name": "id", "path": "$.id"}], "options": {"compare": false}},
                   {"url": "/items/{{id}}", "options": {"ignoreFields": ["flavour", "id"]}}]}]""";
        Suite s = suite(items, null);
        Collector c = new Collector();
        engine.run(new RunRequest(s, "target", "base", Selection.all(), new RunOptions(null, "all", null, null, null, null), null), c);
        CaseResult q = c.get("Q");
        assertEquals(Status.FAIL, q.statusEnum());
        assertTrue(q.reason().contains("price"), q.reason());
        assertNotNull(q.steps().get(0).baseline());
        CaseResult w = c.get("W");
        StepResult read = w.steps().get(1);
        assertEquals(Status.FAIL, w.statusEnum(), "names differ (B vs T) on each lane");
        assertTrue(read.diffs().get(0).contains("name"), read.diffs().toString());
    }

    @Test
    void oauth2TokenIsFetchedOnceAndSent() {
        target.tokenCalls.set(0);
        String extra = """
                "authProfiles": [{"name": "svc", "type": "oauth2", "tokenUrl": "%s/token", "clientId": "c", "clientSecret": "s3cret"}],
                "defaults": {"auth": "svc"}""".formatted(target.url());
        StringBuilder cases = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) cases.append(',');
            cases.append("{\"type\": \"case\", \"key\": \"S" + i + "\", \"steps\": [{\"url\": \"/secure\", \"expect\": {\"status\": \"200\"}}]}");
        }
        Collector c = new Collector();
        RunSummary sum = engine.run(request(suite("[" + cases + "]", extra)), c);
        assertEquals(6, sum.count(Status.PASS), () -> c.cases.toString());
        assertEquals(1, target.tokenCalls.get());
    }

    @Test
    void headersAndFormBodiesFollowTheDefaultsChain() {
        String extra = "\"defaults\": {\"headers\": [{\"key\": \"X-Suite\", \"value\": \"s\"}, {\"key\": \"X-Over\", \"value\": \"suite\"}]}";
        String items = """
                [{"type": "folder", "name": "F", "defaults": {"headers": [{"key": "X-Over", "value": "folder"}]}, "items": [
                  {"type": "case", "key": "H", "steps": [
                    {"url": "/headers", "headers": [{"key": "X-Step", "value": "{{who}}"}],
                     "expect": {"assertions": ["$.x-suite == s", "$.x-over == folder", "$.x-step == T"]}},
                    {"method": "POST", "url": "/form", "body": {"type": "form", "form": [{"key": "a", "value": "1 2"}]},
                     "expect": {"assertions": ["$.received == a=1+2", "$.type contains x-www-form-urlencoded"]}}]}
                ]}]""";
        Collector c = new Collector();
        engine.run(request(suite(items, extra)), c);
        assertEquals(Status.PASS, c.get("H").statusEnum(), () -> c.get("H").reason());
    }

    @Test
    void stopSkipsWhatHasNotStartedButStillRunsTeardown() throws Exception {
        StringBuilder cases = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            if (i > 0) cases.append(',');
            cases.append("{\"type\": \"case\", \"key\": \"S" + i + "\", \"steps\": [{\"url\": \"/slow\"}, {\"url\": \"/slow\"}]}");
        }
        String items = "[{\"type\": \"folder\", \"name\": \"F\", \"run\": {\"cases\": \"serial\"}, \"teardown\": [{\"url\": \"/slow\"}], \"items\": [" + cases + "]}]";
        Collector c = new Collector();
        RunEngine.Handle h = engine.start(request(suite(items, null)), c);
        Thread.sleep(700);
        h.control().stop();
        RunSummary sum = h.done().get();
        assertTrue(sum.stopped());
        assertTrue(sum.count(Status.SKIPPED) > 10, sum.counts().toString());
        assertEquals("teardown:F", c.order.get(c.order.size() - 1));
    }

    @Test
    void selectionByKeyPullsInDependencies() {
        String items = """
                [{"type": "folder", "name": "F", "items": [
                  {"type": "case", "key": "A", "steps": [{"url": "/slow"}]},
                  {"type": "case", "key": "B", "dependsOn": ["A"], "steps": [{"url": "/slow"}]},
                  {"type": "case", "key": "C", "steps": [{"url": "/slow"}]}
                ]}]""";
        Suite s = suite(items, null);
        Collector c = new Collector();
        RunSummary sum = engine.run(new RunRequest(s, "target", null, new Selection(null, List.of("B"), null, null, null),
                RunOptions.defaults(), null), c);
        assertEquals(2, sum.total());
        assertEquals(Map.of("A", "pass", "B", "pass"),
                c.cases.stream().collect(Collectors.toMap(CaseResult::key, CaseResult::status)));
    }

    @Test
    void onlyRequiredPreconditionsBlock() {
        String items = """
                [{"type": "folder", "name": "F", "items": [
                  {"type": "case", "key": "R", "preconditions": [{"url": "/nothing-here", "options": {"required": true}}],
                   "steps": [{"url": "/slow"}]},
                  {"type": "case", "key": "N", "preconditions": [{"url": "/nothing-here"}], "steps": [{"url": "/slow"}]}
                ]}]""";
        Collector c = new Collector();
        engine.run(request(suite(items, null)), c);
        assertEquals(Status.BLOCKED, c.get("R").statusEnum());
        assertEquals("skipped", c.get("R").steps().get(1).status());
        CaseResult n = c.get("N");
        assertEquals(Status.FAIL, n.statusEnum());
        assertTrue(n.reason().startsWith("Precondition 1"), n.reason());
        assertEquals("pass", n.steps().get(1).status());
    }

    @Test
    void stepIndexesStayThoseOfTheEditorWhenStepsAreDisabled() {
        String items = """
                [{"type": "case", "key": "D", "steps": [{"url": "/slow", "disabled": true}, {"url": "/nothing-here"}]}]""";
        Collector c = new Collector();
        engine.run(request(suite(items, null)), c);
        CaseResult d = c.get("D");
        assertEquals(1, d.steps().size());
        assertEquals(1, d.steps().get(0).index());
        assertTrue(d.reason().startsWith("Step 2"), d.reason());
    }

    @Test
    void compareRunsBothLanesAtTheSameTime() {
        String items = """
                [{"type": "case", "key": "P", "steps": [{"url": "/slow", "expect": {"status": "200"}}]}]""";
        Collector c = new Collector();
        engine.run(new RunRequest(suite(items, null), "target", "base", null, RunOptions.defaults(), null), c);
        StepResult s = c.get("P").steps().get(0);
        assertNotNull(s.baseline());
        long both = s.target().timeMs() + s.baseline().timeMs();
        assertTrue(s.durationMs() < both, "step took " + s.durationMs() + " ms, the two calls " + both + " ms");
    }

    @Test
    void stepsRecordTheVariablesTheyUsedAndWhereTheyCameFrom() {
        String items = """
                [{"type": "folder", "name": "F",
                  "setup": [{"name": "seed", "method": "POST", "url": "/items", "body": {"type": "json", "json": {"name": "shared"}},
                             "extract": [{"name": "sid", "path": "$.id"}]}],
                  "items": [
                    {"type": "case", "key": "V", "steps": [
                      {"name": "read", "url": "/items/{{sid}}", "headers": [{"key": "X-Who", "value": "{{who}}"}],
                       "expect": {"status": "200", "assertions": ["# a comment", "$.name == {{expected}}", "$.id == {{sid}}"]}}
                    ]}
                  ]}]""";
        Suite s = suite(items, "\"variables\": [{\"key\": \"expected\", \"value\": \"shared\"}, {\"key\": \"dbPassword\", \"value\": \"hunter2\"}]");
        Collector c = new Collector();
        engine.run(request(s), c);
        StepResult r = c.get("V").steps().get(0);
        assertEquals("pass", r.status(), r.reason());
        Map<String, VarUse> vars = r.target().vars().stream().collect(Collectors.toMap(VarUse::name, v -> v));
        VarUse sid = vars.get("sid");
        assertEquals("setup", sid.from().kind());
        assertEquals("F", sid.from().owner());
        assertEquals(1, sid.from().step());
        assertEquals("seed", sid.from().name());
        assertEquals(List.of("URL", "Check 3"), sid.usedIn());
        assertEquals("env", vars.get("who").from().kind());
        assertEquals("target", vars.get("who").from().owner());
        assertEquals(List.of("Header X-Who"), vars.get("who").usedIn());
        assertEquals("suite", vars.get("expected").from().kind());
        assertEquals(List.of("Check 2"), vars.get("expected").usedIn());
        assertEquals(target.url(), vars.get("baseUrl").value());
        assertEquals("T", c.header.variables().get("target").get("who"));
        assertEquals("••••••", c.header.variables().get("target").get("dbPassword"));
    }

    @Test
    void unresolvedVariablesAreRecordedAsUnset() {
        String items = """
                [{"type": "case", "key": "U", "steps": [{"url": "/items/{{nothing}}"}]}]""";
        Collector c = new Collector();
        engine.run(request(suite(items, null)), c);
        StepResult r = c.get("U").steps().get(0);
        assertEquals("error", r.status());
        VarUse u = r.target().vars().stream().filter(v -> v.name().equals("nothing")).findFirst().orElseThrow();
        assertNull(u.value());
        assertEquals("unset", u.from().kind());
    }

    @Test
    void disabledCasesAndStepsAreListedWhereTheySit() {
        String items = """
                [{"type": "case", "key": "A", "steps": [{"url": "/slow"}]},
                 {"type": "case", "key": "B", "disabled": true, "steps": [{"url": "/slow"}]},
                 {"type": "folder", "name": "Off", "disabled": true, "items": [
                    {"type": "case", "key": "C", "steps": [{"url": "/slow"}]}]},
                 {"type": "case", "key": "D", "steps": [{"url": "/slow", "disabled": true}, {"url": "/slow"}],
                  "cleanup": [{"name": "tidy", "url": "/slow", "disabled": true}]}]""";
        Collector c = new Collector();
        engine.run(request(suite(items, null)), c);
        List<DisabledCase> off = c.header.disabled();
        assertEquals(List.of("B", "C"), off.stream().map(DisabledCase::key).toList());
        assertEquals(1, off.get(0).position());
        assertNull(off.get(0).folder());
        assertEquals(1, off.get(1).position());
        assertEquals("Off", off.get(1).folder());
        assertEquals(List.of("Off"), off.get(1).path());
        CaseResult d = c.get("D");
        assertEquals(List.of("step:0", "cleanup:0"), d.disabledSteps().stream().map(x -> x.phase() + ":" + x.index()).toList());
        assertNull(c.get("A").disabledSteps());
    }

    @Test
    void tokensAreRecordedAsSentButBasicCredentialsAreMasked() {
        String items = """
                [{"type": "case", "key": "T", "steps": [
                   {"url": "/headers", "headers": [{"key": "Authorization", "value": "Bearer abc.def.ghi-123456"}]},
                   {"url": "/headers", "headers": [{"key": "Authorization", "value": "Basic dXNlcjpwYXNzd29yZA=="}]}]}]""";
        Collector c = new Collector();
        engine.run(request(suite(items, null)), c);
        List<StepResult> steps = c.get("T").steps();
        String bearer = steps.get(0).target().requestHeaders().stream().filter(h -> h.key().equals("Authorization"))
                .map(KeyValue::value).findFirst().orElseThrow();
        String basic = steps.get(1).target().requestHeaders().stream().filter(h -> h.key().equals("Authorization"))
                .map(KeyValue::value).findFirst().orElseThrow();
        assertEquals("Bearer abc.def.ghi-123456", bearer);
        assertFalse(basic.contains("dXNlcjpwYXNzd29yZA=="), basic);
    }
}
