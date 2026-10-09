package com.fpt.comparison_tool.engine;

import com.fpt.comparison_tool.result.AssertionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssertionEvaluatorTest {

    static final String BODY = """
            {"id": 7, "name": "Alpha", "price": 12.50, "active": true, "nothing": null, "tags": ["a@x.com", "b@x.com"],
             "orders": [{"order_id": "ORD-1", "status": "confirmed", "total": 150, "currency": "USD"},
                        {"order_id": "ORD-2", "status": "pending", "total": 40, "currency": "USD"}],
             "pricingData": {"superAllInDetailsDtos": [{"detailKey": "INS07", "detailValue": "USD 100.00"}]}}""";

    static boolean pass(String line) {
        List<AssertionResult> r = AssertionEvaluator.evaluate(List.of(line), BODY, Map.of("Content-Type", List.of("application/json")));
        return r.get(0).pass();
    }

    @Test
    void everyOperatorOfThe1xDslStillWorks() {
        assertTrue(pass("$.id == 7"));
        assertTrue(pass("$.price == 12.5"));
        assertTrue(pass("$.name == \"Alpha\""));
        assertTrue(pass("$.name != \"Beta\""));
        assertTrue(pass("$.active == true"));
        assertTrue(pass("$.name exists"));
        assertTrue(pass("$.missing not exists"));
        assertTrue(pass("$.nothing is null"));
        assertTrue(pass("$.name not null"));
        assertTrue(pass("$.name contains \"lph\""));
        assertTrue(pass("$.name not contains \"zzz\""));
        assertTrue(pass("$.id > 6"));
        assertTrue(pass("$.id >= 7"));
        assertTrue(pass("$.id < 8"));
        assertTrue(pass("$.id <= 7"));
        assertTrue(pass("$.orders.length == 2"));
        assertTrue(pass("$.name type == string"));
        assertTrue(pass("$.orders type == array"));
        assertTrue(pass("$.orders[*].currency all == \"USD\""));
        assertTrue(pass("$.orders[*].status any == \"confirmed\""));
        assertTrue(pass("$.orders[*].order_id contains \"ORD-2\""));
        assertTrue(pass("$.tags[*] any == \"a@x.com\""));
        assertTrue(pass("$.orders[order_id=\"ORD-1\"].status == \"confirmed\""));
        assertTrue(pass("$.orders[order_id=\"ORD-1\"].total > 100"));
        assertTrue(pass("$.pricingData.superAllInDetailsDtos[detailKey=\"INS07\"].detailValue == \"USD 100.00\""));
        assertTrue(pass("$.orders[0].order_id == ORD-1"));
        assertTrue(pass("$.orders[-1].order_id == ORD-2"));
        assertFalse(pass("$.orders[*].status all == \"confirmed\""));
        assertFalse(pass("$.tags[*] == \"a@x.com\""));
    }

    @Test
    void newOperators() {
        assertTrue(pass("$.name matches \"^Al.*a$\""));
        assertTrue(pass("$.name not matches \"^Z\""));
        assertTrue(pass("header Content-Type contains json"));
        assertFalse(pass("header X-Missing exists"));
    }

    @Test
    void statusExpectations() {
        assertTrue(AssertionEvaluator.status("2xx", 204).pass());
        assertTrue(AssertionEvaluator.status("200,404", 404).pass());
        assertFalse(AssertionEvaluator.status("200", 500).pass());
        assertTrue(AssertionEvaluator.status("<400", 302).pass());
        assertEquals("expected 200, got 500", AssertionEvaluator.status("200", 500).message());
    }

    @Test
    void syntaxCheck() {
        assertNull(AssertionEvaluator.checkSyntax("$.a == 1"));
        assertNull(AssertionEvaluator.checkSyntax("$.a[*] any == 1"));
        assertTrue(AssertionEvaluator.checkSyntax("$.a[*] == 1").contains("any or all"));
        assertTrue(AssertionEvaluator.checkSyntax("$.a foo 1").contains("unknown operator"));
        assertTrue(AssertionEvaluator.checkSyntax("a == 1").contains("starts with"));
    }

    @Test
    void valuePaths() {
        assertEquals("7", ValuePaths.read("$.id", 200, Map.of(), BODY));
        assertEquals("pending", ValuePaths.read("$.orders[-1].status", 200, Map.of(), BODY));
        assertEquals("150", ValuePaths.read("$.orders[order_id=ORD-1].total", 200, Map.of(), BODY));
        assertEquals("2", ValuePaths.read("$.orders.length", 200, Map.of(), BODY));
        assertEquals("201", ValuePaths.read("$status", 201, Map.of(), BODY));
        assertEquals("application/json", ValuePaths.read("$header.content-type", 200, Map.of("Content-Type", List.of("application/json")), BODY));
        assertEquals("{\"order_id\":\"ORD-1\",\"status\":\"confirmed\",\"total\":150,\"currency\":\"USD\"}",
                ValuePaths.read("$.orders[0]", 200, Map.of(), BODY));
        assertNull(ValuePaths.read("$.nothing", 200, Map.of(), BODY));
    }

    @Test
    void templates() {
        java.util.Set<String> missing = new java.util.HashSet<>();
        String out = Templates.resolve("/a/{{x}}/{{ y }}/{{z}}", n -> n.equals("x") ? "1" : n.equals("y") ? "2" : null, missing);
        assertEquals("/a/1/2/{{z}}", out);
        assertEquals(java.util.Set.of("z"), missing);
        assertEquals(36, Templates.resolve("{{$guid}}", n -> null, null).length());
    }
}
