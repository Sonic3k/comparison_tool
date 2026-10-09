package com.fpt.comparison_tool.io;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.comparison_tool.model.AuthProfile;
import com.fpt.comparison_tool.model.Body;
import com.fpt.comparison_tool.model.Defaults;
import com.fpt.comparison_tool.model.Environment;
import com.fpt.comparison_tool.model.Folder;
import com.fpt.comparison_tool.model.KeyValue;
import com.fpt.comparison_tool.model.Node;
import com.fpt.comparison_tool.model.Step;
import com.fpt.comparison_tool.model.Suite;
import com.fpt.comparison_tool.model.TestCase;
import com.fpt.comparison_tool.suite.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * Postman collection v2.1: folders stay folders, a test case becomes a folder of its steps, setup and teardown become
 * the first and last sub-folder. Assertions and extracts run through the tool's DSL, kept in the ct_lib collection
 * variable. Headers and auth are written on each request as the tool would send them to the chosen environment.
 */
public final class PostmanExport {

    private PostmanExport() {
    }

    public static String export(Suite suite, String envName) {
        Environment env = ExportSupport.env(suite, envName);
        ObjectNode root = Json.MAPPER.createObjectNode();
        ObjectNode info = root.putObject("info");
        info.put("name", suite.name());
        if (suite.description() != null) info.put("description", suite.description());
        info.put("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json");

        ArrayNode vars = root.putArray("variable");
        if (env.baseUrl() != null) variable(vars, "baseUrl", env.baseUrl());
        for (KeyValue v : suite.variables()) if (v.active()) variable(vars, v.key(), v.value());
        for (KeyValue v : env.variables()) if (v.active()) variable(vars, v.key(), v.value());
        variable(vars, "ct_lib", ExportSupport.library());

        ArrayNode items = root.putArray("item");
        List<Defaults> chain = List.of(suite.defaultsOrEmpty());
        if (!suite.setup().isEmpty()) items.add(group("00 Suite setup", steps(suite, env, chain, suite.setup(), "")));
        addItems(suite, env, chain, suite.items(), items);
        if (!suite.teardown().isEmpty()) items.add(group("zz Suite teardown", steps(suite, env, chain, suite.teardown(), "")));
        return Json.pretty(root);
    }

    private static void addItems(Suite suite, Environment env, List<Defaults> chain, List<Node> nodes, ArrayNode out) {
        for (Node n : nodes) {
            if (!n.active()) continue;
            if (n instanceof Folder f) {
                List<Defaults> next = new ArrayList<>(chain);
                next.add(f.defaultsOrEmpty());
                ArrayNode children = Json.MAPPER.createArrayNode();
                if (!f.setup().isEmpty()) children.add(group("Setup", steps(suite, env, next, f.setup(), "")));
                addItems(suite, env, next, f.items(), children);
                if (!f.teardown().isEmpty()) children.add(group("Teardown", steps(suite, env, next, f.teardown(), "")));
                ObjectNode g = group(f.name(), children);
                if (f.description() != null) g.put("description", f.description());
                out.add(g);
            } else if (n instanceof TestCase tc) {
                ArrayNode children = Json.MAPPER.createArrayNode();
                children.addAll(steps(suite, env, chain, tc.preconditions(), "[pre] "));
                children.addAll(steps(suite, env, chain, tc.steps(), ""));
                children.addAll(steps(suite, env, chain, tc.cleanup(), "[cleanup] "));
                ObjectNode g = group(tc.label(), children);
                if (tc.description() != null) g.put("description", tc.description());
                out.add(g);
            }
        }
    }

    private static ArrayNode steps(Suite suite, Environment env, List<Defaults> chain, List<Step> steps, String prefix) {
        ArrayNode arr = Json.MAPPER.createArrayNode();
        for (Step s : steps) {
            if (!s.active()) continue;
            ObjectNode item = arr.addObject();
            item.put("name", prefix + (s.name().isBlank() ? s.method() + " " + s.url() : s.name()));
            ObjectNode req = item.putObject("request");
            req.put("method", s.method());
            ArrayNode hs = req.putArray("header");
            for (KeyValue h : ExportSupport.headers(env, chain, s)) {
                ObjectNode ho = hs.addObject();
                ho.put("key", h.key());
                ho.put("value", h.value());
            }
            StringBuilder url = new StringBuilder(ExportSupport.url(s));
            boolean first = !url.toString().contains("?");
            for (KeyValue q : s.query()) {
                if (!q.active()) continue;
                url.append(first ? '?' : '&').append(q.key()).append('=').append(q.value());
                first = false;
            }
            req.put("url", url.toString());
            Body b = s.body();
            if (b != null) {
                ObjectNode body = req.putObject("body");
                if (Body.FORM.equals(b.type())) {
                    body.put("mode", "urlencoded");
                    ArrayNode form = body.putArray("urlencoded");
                    for (KeyValue f : b.form()) {
                        if (!f.active()) continue;
                        ObjectNode fo = form.addObject();
                        fo.put("key", f.key());
                        fo.put("value", f.value());
                    }
                } else {
                    body.put("mode", "raw");
                    body.put("raw", ExportSupport.bodyText(s));
                    if (Body.JSON.equals(b.type())) body.putObject("options").putObject("raw").put("language", "json");
                }
            }
            req.set("auth", auth(ExportSupport.auth(suite, env, chain, s)));
            if (s.notes() != null) req.put("description", s.notes());
            ArrayNode events = item.putArray("event");
            ObjectNode test = events.addObject();
            test.put("listen", "test");
            ObjectNode script = test.putObject("script");
            script.put("type", "text/javascript");
            ArrayNode exec = script.putArray("exec");
            exec.add("eval(pm.collectionVariables.get(\"ct_lib\"));");
            exec.add("ct.postman(pm, " + ExportSupport.spec(s, true) + ");");
        }
        return arr;
    }

    private static ObjectNode auth(AuthProfile p) {
        ObjectNode a = Json.MAPPER.createObjectNode();
        if (p == null) {
            a.put("type", "noauth");
            return a;
        }
        switch (p.type()) {
            case AuthProfile.BASIC -> {
                a.put("type", "basic");
                ArrayNode arr = a.putArray("basic");
                kv(arr, "username", p.username());
                kv(arr, "password", p.password());
            }
            case AuthProfile.BEARER -> {
                a.put("type", "bearer");
                kv(a.putArray("bearer"), "token", p.token());
            }
            case AuthProfile.OAUTH2 -> {
                a.put("type", "oauth2");
                ArrayNode arr = a.putArray("oauth2");
                String grant = p.resolvedGrant();
                kv(arr, "grant_type", switch (grant) {
                    case "password" -> "password_credentials";
                    case "refresh_token" -> "client_credentials";
                    default -> "client_credentials";
                });
                kv(arr, "accessTokenUrl", p.tokenUrl());
                kv(arr, "clientId", p.clientId());
                kv(arr, "clientSecret", p.clientSecret());
                kv(arr, "scope", p.scope());
                kv(arr, "username", p.username());
                kv(arr, "password", p.password());
                kv(arr, "client_authentication", "body");
                kv(arr, "addTokenTo", "header");
            }
            default -> a.put("type", "noauth");
        }
        return a;
    }

    private static void kv(ArrayNode arr, String key, String value) {
        if (value == null) return;
        ObjectNode o = arr.addObject();
        o.put("key", key);
        o.put("value", value);
        o.put("type", "string");
    }

    private static ObjectNode group(String name, ArrayNode children) {
        ObjectNode g = Json.MAPPER.createObjectNode();
        g.put("name", name);
        g.set("item", children);
        return g;
    }

    private static void variable(ArrayNode vars, String key, String value) {
        ObjectNode v = vars.addObject();
        v.put("key", key);
        v.put("value", value == null ? "" : value);
    }
}
