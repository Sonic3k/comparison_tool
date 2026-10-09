package com.fpt.comparison_tool.suite;

import com.fpt.comparison_tool.model.Folder;
import com.fpt.comparison_tool.model.Suite;
import com.fpt.comparison_tool.model.TestCase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V1ConverterTest {

    static String req(String tc, String phase, String method, String endpoint, String extract, String body, String mode) {
        return """
                {"id": "%s-%s", "testCaseId": "%s", "name": "%s %s", "enabled": true, "verificationMode": "%s", "phase": "%s",
                 "method": "%s", "endpoint": "%s", "jsonBody": %s, "headers": "X-User-Id: U1",
                 "extractVariables": "%s", "automationConfig": {"expectedStatus": "2xx", "expectedBody": "$.ok exists"}}"""
                .formatted(tc, Math.abs(endpoint.hashCode() + phase.hashCode()), tc, method, endpoint, mode, phase, method, endpoint,
                        body == null ? "null" : "\"" + body.replace("\"", "\\\"") + "\"", extract == null ? "" : extract);
    }

    @Test
    void groupsBecomeFoldersAndHelpersBecomeHooks() {
        String v1 = """
                {"settings": {"suiteName": "S", "executionConfig": {"mode": "source_first", "timeout": 60000,
                   "verificationMode": "automation", "sourceEnvironment": "legacy", "targetEnvironment": "modern"}},
                 "environments": [{"name": "modern", "url": "http://m", "headers": [{"key": "Accept", "value": "application/json"}]},
                                  {"name": "legacy", "url": "http://l"}],
                 "authProfiles": [{"name": "none", "type": "none"}],
                 "testGroups": [
                   {"name": "Global Setup", "testRequests": [%s]},
                   {"name": "G1", "enabled": true, "testCaseDefs": [{"id": "TC1", "name": "First"}], "testRequests": [
                      %s, %s, %s, %s, %s, %s, %s
                   ]},
                   {"name": "Global Teardown", "testRequests": [%s]}
                 ]}""".formatted(
                req("GS", "setup", "POST", "/seed", "g=$.id", "{}", "none"),
                req("SETUP-A", "test", "POST", "/a", "aid=$.id", "{\"n\": 1}", "automation"),
                req("TC1", "test", "GET", "/a/{{aid}}", null, null, "automation"),
                req("TC1", "test", "POST", "/b", "bid=$.id", "{}", "automation"),
                req("TEARDOWN-B", "test", "DELETE", "/b/{{bid}}", null, null, "automation"),
                req("TC2", "test", "GET", "/a/{{aid}}", "x=$.x", null, "automation"),
                req("TC3", "test", "GET", "/x/{{x}}", null, null, "automation"),
                req("TC4", "test", "GET", "/skipped", null, null, "none"),
                req("GT", "teardown", "DELETE", "/seed/{{g}}", null, null, "none"));
        V1Converter.Result r = SuiteJson.parse(v1).format().equals("v1") ? V1Converter.convert(
                Json.MAPPER.valueToTree(Json.MAPPER.convertValue(parse(v1), Object.class))) : null;
        Suite s = r.suite();
        assertEquals(1, s.setup().size());
        assertEquals(1, s.teardown().size());
        Folder g1 = (Folder) s.items().get(0);
        assertEquals("G1", g1.name());
        assertEquals("serial", s.settings().run().cases());
        assertEquals(1, g1.setup().size(), "the leading SETUP case joins the folder setup");
        List<TestCase> cases = g1.items().stream().map(n -> (TestCase) n).toList();
        assertEquals(List.of("TC1", "TC2", "TC3", "TC4"), cases.stream().map(TestCase::key).toList());
        TestCase tc1 = cases.get(0);
        assertEquals("First", tc1.name());
        assertEquals(1, tc1.cleanup().size(), "TEARDOWN-B right after TC1 becomes its cleanup");
        assertEquals(List.of("TC2"), cases.get(2).dependsOn(), "TC3 reads x extracted by TC2");
        assertEquals("folder", cases.get(1).steps().get(0).extract().get(0).scope());
        assertTrue(cases.get(3).disabled(), "verification mode none was skipped by the automation filter");
        assertNull(cases.get(0).steps().get(0).headers().stream().filter(h -> h.key().equals("X-User-Id")).findFirst().orElse(null),
                "a header on every step moves to the defaults");
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("dependsOn added: 1")), r.notes().toString());
    }

    static com.fasterxml.jackson.databind.JsonNode parse(String s) {
        try {
            return Json.MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
