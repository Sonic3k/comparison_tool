package com.fpt.comparison_tool.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.comparison_tool.model.Suite;
import com.fpt.comparison_tool.result.CaseResult;
import com.fpt.comparison_tool.result.RunData;
import com.fpt.comparison_tool.result.RunFile;
import com.fpt.comparison_tool.result.RunHeader;
import com.fpt.comparison_tool.result.RunSummary;
import com.fpt.comparison_tool.suite.Json;
import com.fpt.comparison_tool.suite.SuiteJson;
import com.fpt.comparison_tool.suite.SuiteValidator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoundTripTest {

    static final String SUITE = """
            {"name": "RT", "settings": {"concurrency": 3, "env": "dev", "run": {"cases": "serial"}, "compare": {"ignoreFields": ["ts"]}},
             "environments": [{"name": "dev", "baseUrl": "http://dev", "auth": "basic1", "headers": [{"key": "A", "value": "1"}],
                               "variables": [{"key": "u", "value": "x"}]}],
             "authProfiles": [{"name": "basic1", "type": "basic", "username": "u", "password": "${PW:p}"}],
             "variables": [{"key": "k", "value": "v"}],
             "defaults": {"headers": [{"key": "X", "value": "y"}], "timeoutMs": 5000},
             "setup": [{"name": "s1", "url": "/s"}],
             "items": [
               {"type": "folder", "name": "F1", "description": "d", "run": {"cases": "parallel"},
                "setup": [{"name": "fs", "method": "POST", "url": "/fs", "body": {"type": "json", "json": {"a": [1, 2.50, "{{u}}"]}},
                           "extract": [{"name": "id", "path": "$.id"}], "options": {"required": true}}],
                "items": [
                  {"type": "case", "key": "C1", "name": "one", "tags": ["smoke"], "steps": [
                    {"name": "get", "url": "/x/{{id}}", "query": [{"key": "q", "value": "1"}],
                     "expect": {"status": "2xx", "assertions": ["$.a == 1", "$.b[*] any == \\"x\\""]},
                     "options": {"retry": {"times": 2, "intervalMs": 500}, "compare": false}}],
                   "cleanup": [{"name": "del", "method": "DELETE", "url": "/x/{{id}}"}]},
                  {"type": "folder", "name": "Sub", "items": [
                    {"type": "case", "key": "C2", "dependsOn": [], "steps": [
                      {"name": "form", "method": "POST", "url": "/f", "body": {"type": "form", "form": [{"key": "a", "value": "b"}]},
                       "extract": [{"name": "z", "path": "$.z", "scope": "folder"}]}]}]},
                  {"type": "case", "key": "C3", "disabled": true, "steps": [{"name": "t", "method": "PUT", "url": "/t",
                    "body": {"type": "text", "raw": "hello {{u}}"}}]}
                ]}
             ]}""";

    @Test
    void excelRoundTripKeepsTheSuite() throws Exception {
        Suite s = SuiteJson.parse(SUITE).suite();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelSuite.write(s, out);
        Suite back = ExcelSuite.read(new ByteArrayInputStream(out.toByteArray())).suite();
        JsonNode a = Json.MAPPER.valueToTree(s);
        JsonNode b = Json.MAPPER.valueToTree(back);
        assertEquals(a.toPrettyString(), b.toPrettyString());
    }

    @Test
    void jsonRoundTripIsStable() {
        Suite s = SuiteJson.parse(SUITE).suite();
        String once = SuiteJson.write(s);
        String twice = SuiteJson.write(SuiteJson.parse(once).suite());
        assertEquals(once, twice);
        assertTrue(once.contains("2.50"), "decimals keep their scale");
    }

    @Test
    void validatorFindsReferenceProblems() {
        String bad = SUITE.replace("\"dependsOn\": []", "\"dependsOn\": [\"NOPE\"]").replace("\"auth\": \"basic1\"", "\"auth\": \"ghost\"");
        List<SuiteValidator.Issue> issues = SuiteValidator.validate(SuiteJson.parse(bad).suite());
        assertTrue(issues.stream().anyMatch(i -> i.message().contains("NOPE")), issues.toString());
        assertTrue(issues.stream().anyMatch(i -> i.message().contains("ghost")), issues.toString());
        assertEquals(0, SuiteValidator.validate(SuiteJson.parse(SUITE).suite()).stream().filter(i -> i.level().equals("error")).count());
    }

    @Test
    void runFileRoundTrip() throws Exception {
        RunHeader h = new RunHeader(RunHeader.SCHEMA, "r1", "RT", null, "dev", null, "2026-10-09T10:00:00+07:00", "all", 3,
                "failed", "run", 1, "t", List.of("c1"));
        CaseResult c = new CaseResult("c1", "C1", "one", "f1", List.of("F1"), List.of(), "fail", "boom", null, null, 12L,
                List.of(), new CaseResult.Triage("defect", "BE rounding"));
        RunData d = new RunData(h, List.of(), List.of(c), new RunSummary(1, Map.of("fail", 1), null, 10L, null, null));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RunFile.write(d, out);
        RunData back = RunFile.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("defect", back.cases().get(0).triage().label());
        assertEquals("r1", back.header().runId());
    }

    @Test
    void exportsHaveTheFolderTree() throws Exception {
        Suite s = SuiteJson.parse(SUITE).suite();
        JsonNode pm = Json.MAPPER.readTree(PostmanExport.export(s, null));
        assertEquals("RT", pm.path("info").path("name").asText());
        assertEquals("00 Suite setup", pm.path("item").get(0).path("name").asText());
        JsonNode f1 = pm.path("item").get(1);
        assertEquals("F1", f1.path("name").asText());
        assertEquals("Setup", f1.path("item").get(0).path("name").asText());
        assertTrue(pm.toString().contains("ct.postman(pm"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BrunoExport.export(s, out);
        List<String> names = new java.util.ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) names.add(e.getName());
        }
        assertTrue(names.contains("bruno.json"));
        assertTrue(names.contains("lib/ct.js"));
        assertTrue(names.contains("environments/dev.bru"));
        assertTrue(names.stream().anyMatch(n -> n.startsWith("01 F1/00 Setup/")), names.toString());
        assertTrue(names.stream().anyMatch(n -> n.startsWith("01 F1/02 Sub/01 C2/")), names.toString());
        assertTrue(names.stream().anyMatch(n -> n.contains("C1 one/")), names.toString());
    }

    @Test
    void emptyJsonBodyIsKept() {
        String json = """
                {"name": "E", "environments": [{"name": "e", "baseUrl": "http://x"}],
                 "items": [{"type": "case", "key": "K", "steps": [{"method": "POST", "url": "/s", "body": {"type": "json", "json": {}}},
                                                                   {"method": "POST", "url": "/t", "body": {"type": "json", "json": []}}]}]}""";
        Suite s = SuiteJson.parse(json).suite();
        Suite back = SuiteJson.parse(SuiteJson.write(s)).suite();
        var steps = ((com.fpt.comparison_tool.model.TestCase) back.items().get(0)).steps();
        assertEquals("{}", steps.get(0).body().json().toString());
        assertEquals("[]", steps.get(1).body().json().toString());
    }
}
