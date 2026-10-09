package com.fpt.comparison_tool.io;

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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Bruno collection as a zip (bruno.json at the root): one directory per folder and per test case, one .bru file per
 * step, an environment file per environment, and lib/ct.js with the tool's assertion DSL used by the tests and
 * post-response scripts (Bruno's Developer Mode is needed for require).
 */
public final class BrunoExport {

    private BrunoExport() {
    }

    public static void export(Suite suite, OutputStream os) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        Map<String, Object> bruno = new LinkedHashMap<>();
        bruno.put("version", "1");
        bruno.put("name", suite.name());
        bruno.put("type", "collection");
        bruno.put("ignore", List.of("node_modules", ".git"));
        files.put("bruno.json", Json.pretty(bruno));

        StringBuilder col = new StringBuilder();
        Defaults sd = suite.defaultsOrEmpty();
        dict(col, "headers", sd.headers());
        col.append("auth {\n  mode: none\n}\n\n");
        col.append("docs {\n  Exported from ").append(suite.name()).append(" by Comparison Tool.\n")
                .append("  Tests and post-response scripts use lib/ct.js: run Bruno in Developer Mode.\n}\n");
        files.put("collection.bru", col.toString());
        for (Environment e : suite.environments()) {
            StringBuilder env = new StringBuilder("vars {\n");
            if (e.baseUrl() != null) env.append("  baseUrl: ").append(oneLine(e.baseUrl())).append('\n');
            for (KeyValue v : suite.variables()) if (v.active()) env.append("  ").append(v.key()).append(": ").append(oneLine(v.value())).append('\n');
            for (KeyValue v : e.variables()) if (v.active()) env.append("  ").append(v.key()).append(": ").append(oneLine(v.value())).append('\n');
            env.append("}\n");
            files.put("environments/" + safe(e.name()) + ".bru", env.toString());
        }
        files.put("lib/ct.js", ExportSupport.library());

        Environment env = ExportSupport.env(suite, null);
        List<Defaults> chain = List.of(sd);
        Set<String> used = new HashSet<>();
        int seq = 1;
        if (!suite.setup().isEmpty()) {
            String dir = unique(used, "00 Suite setup");
            folderFile(files, dir, "00 Suite setup", seq++, List.of());
            steps(files, suite, env, chain, dir, suite.setup(), "", 1);
        }
        seq = items(files, suite, env, chain, "", suite.items(), seq, used);
        if (!suite.teardown().isEmpty()) {
            String dir = unique(used, "zz Suite teardown");
            folderFile(files, dir, "zz Suite teardown", seq, List.of());
            steps(files, suite, env, chain, dir, suite.teardown(), "", 1);
        }

        try (ZipOutputStream zip = new ZipOutputStream(os)) {
            for (Map.Entry<String, String> f : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(f.getKey()));
                zip.write(f.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private static int items(Map<String, String> files, Suite suite, Environment env, List<Defaults> chain, String base,
                             List<Node> nodes, int seq, Set<String> used) {
        int position = 0;
        for (Node n : nodes) {
            if (!n.active()) continue;
            position++;
            if (n instanceof Folder f) {
                String dir = unique(used, base + String.format("%02d ", position) + safe(f.name()));
                folderFile(files, dir, f.name(), seq++, f.defaultsOrEmpty().headers());
                List<Defaults> next = new ArrayList<>(chain);
                next.add(f.defaultsOrEmpty());
                int inner = 1;
                if (!f.setup().isEmpty()) {
                    String sdir = unique(used, dir + "/00 Setup");
                    folderFile(files, sdir, "Setup", inner++, List.of());
                    steps(files, suite, env, next, sdir, f.setup(), "", 1);
                }
                inner = items(files, suite, env, next, dir + "/", f.items(), inner, used);
                if (!f.teardown().isEmpty()) {
                    String tdir = unique(used, dir + "/zz Teardown");
                    folderFile(files, tdir, "Teardown", inner, List.of());
                    steps(files, suite, env, next, tdir, f.teardown(), "", 1);
                }
            } else if (n instanceof TestCase tc) {
                String dir = unique(used, base + String.format("%02d ", position) + safe(tc.label()));
                folderFile(files, dir, tc.label(), seq++, List.of());
                int s = steps(files, suite, env, chain, dir, tc.preconditions(), "pre ", 1);
                s = steps(files, suite, env, chain, dir, tc.steps(), "", s);
                steps(files, suite, env, chain, dir, tc.cleanup(), "cleanup ", s);
            }
        }
        return seq;
    }

    private static int steps(Map<String, String> files, Suite suite, Environment env, List<Defaults> chain, String dir,
                             List<Step> steps, String prefix, int seq) {
        Set<String> names = new HashSet<>();
        for (Step s : steps) {
            if (!s.active()) continue;
            String name = prefix + (s.name().isBlank() ? s.method() + " " + s.url() : s.name());
            String file = dir + "/" + uniqueName(names, String.format("%03d ", seq) + safe(name)) + ".bru";
            files.put(file, request(suite, env, chain, s, name, seq++));
        }
        return seq;
    }

    private static String request(Suite suite, Environment env, List<Defaults> chain, Step s, String name, int seq) {
        StringBuilder b = new StringBuilder();
        b.append("meta {\n  name: ").append(oneLine(name)).append("\n  type: http\n  seq: ").append(seq).append("\n}\n\n");
        String bodyMode = "none";
        if (s.body() != null) {
            bodyMode = switch (s.body().type()) {
                case Body.FORM -> "formUrlEncoded";
                case Body.TEXT -> "text";
                default -> "json";
            };
        }
        AuthProfile auth = ExportSupport.auth(suite, env, chain, s);
        String authMode = auth == null ? "none" : switch (auth.type()) {
            case AuthProfile.BASIC -> "basic";
            case AuthProfile.BEARER -> "bearer";
            case AuthProfile.OAUTH2 -> "oauth2";
            default -> "none";
        };
        b.append(s.method().toLowerCase(Locale.ROOT)).append(" {\n  url: ").append(oneLine(ExportSupport.url(s)))
                .append("\n  body: ").append(bodyMode).append("\n  auth: ").append(authMode).append("\n}\n\n");
        dict(b, "params:query", s.query());
        List<KeyValue> headers = new ArrayList<>(env.headers());
        for (Defaults d : chain) headers.addAll(d.headers());
        headers.addAll(s.headers());
        dict(b, "headers", ExportSupport.headers(env, chain, s));
        if (auth != null) {
            switch (auth.type()) {
                case AuthProfile.BASIC -> b.append("auth:basic {\n  username: ").append(oneLine(auth.username()))
                        .append("\n  password: ").append(oneLine(auth.password())).append("\n}\n\n");
                case AuthProfile.BEARER -> b.append("auth:bearer {\n  token: ").append(oneLine(auth.token())).append("\n}\n\n");
                case AuthProfile.OAUTH2 -> b.append("auth:oauth2 {\n  grant_type: ")
                        .append("password".equals(auth.resolvedGrant()) ? "password" : "client_credentials")
                        .append("\n  access_token_url: ").append(oneLine(auth.tokenUrl()))
                        .append("\n  client_id: ").append(oneLine(auth.clientId()))
                        .append("\n  client_secret: ").append(oneLine(auth.clientSecret()))
                        .append("\n  username: ").append(oneLine(auth.username()))
                        .append("\n  password: ").append(oneLine(auth.password()))
                        .append("\n  scope: ").append(oneLine(auth.scope())).append("\n}\n\n");
                default -> {
                }
            }
        }
        if (s.body() != null) {
            switch (s.body().type()) {
                case Body.FORM -> dict(b, "body:form-urlencoded", s.body().form());
                case Body.TEXT -> block(b, "body:text", s.body().raw());
                default -> block(b, "body:json", ExportSupport.bodyText(s));
            }
        }
        if (!s.extract().isEmpty()) {
            block(b, "script:post-response", "const ct = require(\"./lib/ct.js\");\nct.bruExtract(res, bru, "
                    + ExportSupport.extracts(s) + ");");
        }
        block(b, "tests", "const ct = require(\"./lib/ct.js\");\n"
                + "ct.bruTests(res, test, expect, " + ExportSupport.spec(s, false) + ", (t) => t.replace(/\\{\\{\\s*([^}\\s]+)\\s*\\}\\}/g, "
                + "(m, n) => { const v = bru.getVar(n) ?? bru.getEnvVar(n); return v === undefined ? m : v; }));");
        if (s.notes() != null) block(b, "docs", s.notes());
        return b.toString();
    }

    private static void folderFile(Map<String, String> files, String dir, String name, int seq, List<KeyValue> headers) {
        StringBuilder b = new StringBuilder("meta {\n  name: ").append(oneLine(name)).append("\n  seq: ").append(seq).append("\n}\n\n");
        dict(b, "headers", headers);
        files.put(dir + "/folder.bru", b.toString());
    }

    private static void dict(StringBuilder b, String block, List<KeyValue> entries) {
        if (entries.isEmpty()) return;
        b.append(block).append(" {\n");
        for (KeyValue e : entries) {
            b.append("  ").append(e.disabled() != null ? "~" : "").append(e.key()).append(": ").append(oneLine(e.value())).append('\n');
        }
        b.append("}\n\n");
    }

    private static void block(StringBuilder b, String block, String text) {
        b.append(block).append(" {\n");
        for (String line : (text == null ? "" : text).split("\\r?\\n", -1)) b.append("  ").append(line).append('\n');
        b.append("}\n\n");
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace("\r", " ").replace("\n", " ");
    }

    private static String safe(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (s.length() > 80) s = s.substring(0, 80).trim();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? "item" : s;
    }

    private static String unique(Set<String> used, String path) {
        String p = path;
        int i = 2;
        while (!used.add(p.toLowerCase(Locale.ROOT))) p = path + " (" + i++ + ")";
        return p;
    }

    private static String uniqueName(Set<String> used, String name) {
        String n = name;
        int i = 2;
        while (!used.add(n.toLowerCase(Locale.ROOT))) n = name + " (" + i++ + ")";
        return n;
    }
}
