package com.fpt.comparison_tool.io;

import com.fpt.comparison_tool.model.AuthProfile;
import com.fpt.comparison_tool.model.Defaults;
import com.fpt.comparison_tool.model.Environment;
import com.fpt.comparison_tool.model.Expect;
import com.fpt.comparison_tool.model.Extract;
import com.fpt.comparison_tool.model.KeyValue;
import com.fpt.comparison_tool.model.Step;
import com.fpt.comparison_tool.model.Suite;
import com.fpt.comparison_tool.suite.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** What Postman and Bruno exports share: effective headers and auth of a step, URLs, the DSL library. */
final class ExportSupport {

    private ExportSupport() {
    }

    static Environment env(Suite suite, String envName) {
        Environment e = suite.environment(envName);
        if (e == null) e = suite.environment(suite.settingsOrDefault().env());
        if (e == null && !suite.environments().isEmpty()) e = suite.environments().get(0);
        return e == null ? new Environment("default", null, null, null, null) : e;
    }

    static List<KeyValue> headers(Environment env, List<Defaults> chain, Step step) {
        Map<String, KeyValue> m = new LinkedHashMap<>();
        for (KeyValue h : env.headers()) if (h.active()) m.put(h.key().toLowerCase(Locale.ROOT), h);
        for (Defaults d : chain) for (KeyValue h : d.headers()) if (h.active()) m.put(h.key().toLowerCase(Locale.ROOT), h);
        for (KeyValue h : step.headers()) if (h.active()) m.put(h.key().toLowerCase(Locale.ROOT), h);
        return new ArrayList<>(m.values());
    }

    static AuthProfile auth(Suite suite, Environment env, List<Defaults> chain, Step step) {
        String name = step.auth();
        for (int i = chain.size() - 1; i >= 0 && name == null; i--) name = chain.get(i).auth();
        if (name == null) name = env.auth();
        if (name == null || name.equalsIgnoreCase(AuthProfile.NONE)) return null;
        return suite.authProfile(name);
    }

    static String url(Step step) {
        String u = step.url();
        if (u.toLowerCase(Locale.ROOT).startsWith("http") || u.startsWith("{{")) return u;
        return "{{baseUrl}}" + (u.startsWith("/") || u.isEmpty() ? "" : "/") + u;
    }

    static String spec(Step step, boolean withExtract) {
        Expect ex = step.expectOrEmpty();
        Map<String, Object> spec = new LinkedHashMap<>();
        if (ex.status() != null) spec.put("status", ex.status());
        if (!ex.assertions().isEmpty()) spec.put("assertions", ex.assertions());
        if (withExtract && !step.extract().isEmpty()) {
            List<List<String>> ext = new ArrayList<>();
            for (Extract e : step.extract()) ext.add(List.of(e.name(), e.path()));
            spec.put("extract", ext);
        }
        return Json.compact(spec);
    }

    static String extracts(Step step) {
        List<List<String>> ext = new ArrayList<>();
        for (Extract e : step.extract()) ext.add(List.of(e.name(), e.path()));
        return Json.compact(ext);
    }

    static String library() {
        try (InputStream in = ExportSupport.class.getResourceAsStream("/export/ct-lib.js")) {
            if (in == null) throw new IllegalStateException("ct-lib.js missing from the build");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String bodyText(Step step) {
        if (step.body() == null) return null;
        return step.body().json() != null ? Json.pretty(step.body().json()).stripTrailing() : step.body().raw();
    }
}
