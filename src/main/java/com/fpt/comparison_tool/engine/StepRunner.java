package com.fpt.comparison_tool.engine;

import com.fpt.comparison_tool.model.AuthProfile;
import com.fpt.comparison_tool.model.Body;
import com.fpt.comparison_tool.model.CompareOptions;
import com.fpt.comparison_tool.model.Defaults;
import com.fpt.comparison_tool.model.Expect;
import com.fpt.comparison_tool.model.Extract;
import com.fpt.comparison_tool.model.KeyValue;
import com.fpt.comparison_tool.model.Step;
import com.fpt.comparison_tool.model.StepOptions;
import com.fpt.comparison_tool.model.Suite;
import com.fpt.comparison_tool.result.AssertionResult;
import com.fpt.comparison_tool.result.Exchange;
import com.fpt.comparison_tool.result.Status;
import com.fpt.comparison_tool.result.StepResult;
import org.apache.hc.client5.http.cookie.CookieStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Executes one step on every lane of a run: resolves variables, sends the call, saves extracted variables, then
 * checks the status expectation, the assertions (target) and, in compare runs, the baseline/target diff.
 */
final class StepRunner {

    /** Where a step reads and writes variables and which defaults apply to it. */
    record Context(Suite suite, List<Lane> lanes, Map<String, Scope> read, Map<String, Scope> shared,
                   Map<String, Scope> suiteShared, Map<String, CookieStore> cookies, List<Defaults> defaults,
                   Map<String, String> overrides) {
    }

    record Prepared(String method, String url, List<KeyValue> headers, String body, int timeoutMs,
                    Set<String> unresolved, String error) {
    }

    private record Attempt(Status status, String reason, List<AssertionResult> assertions, List<String> diffs,
                           Map<String, String> extracted, List<String> missed, Map<String, Exchange> exchanges) {
    }

    private static final int DEFAULT_TIMEOUT_MS = 60_000;

    private final HttpExecutor http;
    private final AuthService auth;

    StepRunner(HttpExecutor http, AuthService auth) {
        this.http = http;
        this.auth = auth;
    }

    StepResult run(Step step, String phase, int index, Context ctx, RunControl control) {
        long start = System.currentTimeMillis();
        StepOptions so = step.optionsOrNone();
        if (so.delayMs() != null && !sleep(so.delayMs())) {
            return StepResult.skipped(phase, index, step.name(), "Run stopped");
        }
        int maxAttempts = 1 + (so.retry() == null ? 0 : so.retry().timesOrZero());
        Attempt last = null;
        int attempts = 0;
        for (int a = 1; a <= maxAttempts; a++) {
            attempts = a;
            last = attempt(step, ctx);
            if (last.status == Status.PASS || a == maxAttempts || control.stopped()) break;
            if (!sleep(so.retry().intervalOrDefault())) break;
        }
        Exchange target = null;
        Exchange baseline = null;
        for (Lane lane : ctx.lanes()) {
            Exchange ex = last.exchanges.get(lane.name());
            if (lane.baseline()) baseline = ex;
            else target = ex;
        }
        return new StepResult(phase, index, step.name(), last.status.value(), last.reason, attempts > 1 ? attempts : null,
                System.currentTimeMillis() - start, last.assertions, last.diffs, last.extracted, last.missed, target, baseline);
    }

    private Attempt attempt(Step step, Context ctx) {
        Map<String, Exchange> exchanges = new LinkedHashMap<>();
        Map<String, HttpExecutor.Reply> replies = new LinkedHashMap<>();
        Map<String, String> extracted = new LinkedHashMap<>();
        List<String> missed = new ArrayList<>();
        String error = null;

        List<Lane> lanes = ctx.lanes();
        LaneCall[] calls = new LaneCall[lanes.size()];
        boolean together = lanes.size() > 1 && lanes.stream().map(l -> l.env().name()).distinct().count() == lanes.size();
        if (together) {
            // baseline and target are different systems: call them at the same time
            Thread[] threads = new Thread[lanes.size()];
            for (int i = 0; i < lanes.size(); i++) {
                int k = i;
                threads[i] = Thread.ofVirtual().name("lane-" + lanes.get(k).name()).start(() -> calls[k] = callLane(step, lanes.get(k), ctx));
            }
            boolean interrupted = false;
            for (Thread t : threads) {
                while (true) {
                    try {
                        t.join();
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        } else {
            for (int i = 0; i < lanes.size(); i++) calls[i] = callLane(step, lanes.get(i), ctx);
        }
        for (int i = 0; i < lanes.size(); i++) {
            Lane lane = lanes.get(i);
            LaneCall c = calls[i];
            exchanges.put(lane.name(), c.exchange);
            if (c.reply != null) replies.put(lane.name(), c.reply);
            extracted.putAll(c.extracted);
            missed.addAll(c.missed);
            if (c.error != null && error == null) error = laneLabel(ctx, lane) + c.error;
        }
        if (error != null) {
            return new Attempt(Status.ERROR, error, List.of(), List.of(), extracted, missed, exchanges);
        }

        Lane targetLane = ctx.lanes().stream().filter(l -> !l.baseline()).findFirst().orElseThrow();
        HttpExecutor.Reply t = replies.get(targetLane.name());
        Function<String, String> lookup = lookup(ctx, targetLane);
        Expect ex = step.expectOrEmpty();
        List<AssertionResult> assertions = new ArrayList<>();
        if (ex.status() == null && ex.assertions().isEmpty() && ex.maxTimeMs() == null) {
            assertions.add(AssertionEvaluator.status("<400", t.status()));
        } else if (ex.status() != null) {
            assertions.add(AssertionEvaluator.status(Templates.resolve(ex.status(), lookup, null), t.status()));
        }
        List<String> lines = ex.assertions().stream().map(l -> Templates.resolve(l, lookup, null)).toList();
        assertions.addAll(AssertionEvaluator.evaluate(lines, t.binary() ? null : t.body(), t.headers()));
        if (ex.maxTimeMs() != null) assertions.add(AssertionEvaluator.time(ex.maxTimeMs(), t.timeMs()));

        List<String> diffs = new ArrayList<>();
        Lane baselineLane = ctx.lanes().stream().filter(Lane::baseline).findFirst().orElse(null);
        StepOptions so = step.optionsOrNone();
        if (baselineLane != null && so.compareOn()) {
            HttpExecutor.Reply b = replies.get(baselineLane.name());
            CompareOptions co = ctx.suite().settingsOrDefault().compareOrDefault().withExtraIgnores(so.ignoreFields());
            if (!Boolean.TRUE.equals(co.compareErrorResponses()) && (b.status() >= 500 || t.status() >= 500)) {
                String msg = b.status() >= 500
                        ? "Baseline returned " + b.status() + " - server error, cannot compare"
                        : "Target returned " + t.status() + " - server error, cannot compare";
                return new Attempt(Status.ERROR, msg, assertions, List.of(), extracted, missed, exchanges);
            }
            diffs.addAll(JsonDiff.compare(b.binary() ? null : b.body(), t.binary() ? null : t.body(), b.status(), t.status(), co));
        }

        AssertionResult firstFail = assertions.stream().filter(a -> !a.pass()).findFirst().orElse(null);
        if (firstFail != null) {
            return new Attempt(Status.FAIL, firstFail.expr() + " -> " + firstFail.message(), assertions, diffs,
                    extracted, missed, exchanges);
        }
        if (!diffs.isEmpty()) {
            String reason = diffs.size() == 1 ? diffs.get(0) : diffs.size() + " differences, first: " + diffs.get(0);
            return new Attempt(Status.FAIL, reason, assertions, diffs, extracted, missed, exchanges);
        }
        return new Attempt(Status.PASS, null, assertions, diffs, extracted, missed, exchanges);
    }

    /** What one lane of a step produced. */
    private record LaneCall(Exchange exchange, HttpExecutor.Reply reply, String error, Map<String, String> extracted,
                            List<String> missed) {
    }

    private LaneCall callLane(Step step, Lane lane, Context ctx) {
        Map<String, String> extracted = new LinkedHashMap<>();
        List<String> missed = new ArrayList<>();
        Prepared p;
        try {
            p = prepare(step, lane, ctx);
        } catch (RuntimeException e) {
            String msg = HttpExecutor.describe(e);
            return new LaneCall(new Exchange(lane.env().name(), step.method(), step.url(), null, null, null, null, null, null,
                    null, null, null, msg), null, msg, extracted, missed);
        }
        if (p.error != null || !p.unresolved.isEmpty()) {
            String msg = p.error != null ? p.error : unresolvedMessage(p.unresolved);
            return new LaneCall(new Exchange(lane.env().name(), p.method, p.url, mask(p.headers), p.body,
                    null, null, null, null, null, null, null, msg), null, msg, extracted, missed);
        }
        try {
            HttpExecutor.Reply reply = http.send(new HttpExecutor.Call(p.method, p.url, p.headers, p.body, p.timeoutMs),
                    ctx.cookies().get(lane.name()));
            Exchange ex = new Exchange(lane.env().name(), p.method, p.url, mask(p.headers), p.body,
                    reply.status(), reply.timeMs(), toList(reply.headers()), reply.contentType(), reply.size(),
                    reply.binary() ? "<binary content, " + reply.size() + " bytes>" : reply.body(), null, null);
            extract(step, lane, reply, ctx, extracted, missed);
            return new LaneCall(ex, reply, null, extracted, missed);
        } catch (Exception e) {
            String msg = HttpExecutor.describe(e);
            return new LaneCall(new Exchange(lane.env().name(), p.method, p.url, mask(p.headers), p.body,
                    null, null, null, null, null, null, null, msg), null, msg, extracted, missed);
        }
    }

    // ---------------------------------------------------------------- request preparation

    Prepared prepare(Step step, Lane lane, Context ctx) {
        Function<String, String> lookup = lookup(ctx, lane);
        Set<String> missing = new LinkedHashSet<>();
        String method = step.method();

        String url = Templates.resolve(step.url(), lookup, missing);
        if (!Urls.isAbsolute(url)) {
            String base = Templates.resolve(lane.env().baseUrl(), lookup, missing);
            if (base == null || base.isBlank()) {
                return new Prepared(method, url, List.of(), null, 0, missing,
                        "Environment '" + lane.env().name() + "' has no base URL and the step URL is not absolute");
            }
            url = Urls.join(base, url);
        }
        List<KeyValue> query = new ArrayList<>();
        for (KeyValue q : step.query()) {
            if (q.active()) query.add(KeyValue.of(Templates.resolve(q.key(), lookup, missing), Templates.resolve(q.value(), lookup, missing)));
        }
        if (!query.isEmpty()) url = Urls.withQuery(url, query);

        Map<String, KeyValue> headers = new LinkedHashMap<>();
        for (KeyValue h : lane.env().headers()) putHeader(headers, h, lookup, missing);
        for (Defaults d : ctx.defaults()) for (KeyValue h : d.headers()) putHeader(headers, h, lookup, missing);
        for (KeyValue h : step.headers()) putHeader(headers, h, lookup, missing);

        String body = null;
        Body b = step.body();
        if (b != null) {
            switch (b.type()) {
                case Body.FORM -> {
                    List<KeyValue> fields = new ArrayList<>();
                    for (KeyValue f : b.form()) {
                        if (f.active()) fields.add(KeyValue.of(Templates.resolve(f.key(), lookup, missing), Templates.resolve(f.value(), lookup, missing)));
                    }
                    body = Urls.form(fields);
                    headers.put("content-type", KeyValue.of("Content-Type", "application/x-www-form-urlencoded"));
                }
                case Body.TEXT -> {
                    body = Templates.resolve(b.raw(), lookup, missing);
                    headers.putIfAbsent("content-type", KeyValue.of("Content-Type", "text/plain"));
                }
                default -> {
                    String text = b.json() != null ? b.json().toString() : b.raw();
                    body = Templates.resolve(text, lookup, missing);
                    headers.putIfAbsent("content-type", KeyValue.of("Content-Type", "application/json"));
                }
            }
        }

        String authName = step.auth();
        if (authName == null) {
            for (int i = ctx.defaults().size() - 1; i >= 0 && authName == null; i--) authName = ctx.defaults().get(i).auth();
        }
        if (authName == null) authName = lane.env().auth();
        List<KeyValue> headerList = new ArrayList<>(headers.values());
        if (authName != null && !authName.equalsIgnoreCase(AuthProfile.NONE)) {
            AuthProfile profile = ctx.suite().authProfile(authName);
            if (profile == null) {
                return new Prepared(method, url, headerList, body, 0, missing, "Unknown auth profile '" + authName + "'");
            }
            try {
                String value = auth.authorization(profile, lookup);
                if (value != null) {
                    headerList.removeIf(h -> h.key().equalsIgnoreCase("Authorization"));
                    headerList.add(KeyValue.of("Authorization", value));
                }
            } catch (AuthTokenException e) {
                return new Prepared(method, url, headerList, body, 0, missing, e.getMessage());
            }
        }

        Integer timeout = step.optionsOrNone().timeoutMs();
        for (int i = ctx.defaults().size() - 1; i >= 0 && timeout == null; i--) timeout = ctx.defaults().get(i).timeoutMs();
        return new Prepared(method, url, headerList, body, timeout == null ? DEFAULT_TIMEOUT_MS : timeout, missing, null);
    }

    private static void putHeader(Map<String, KeyValue> headers, KeyValue h, Function<String, String> lookup, Set<String> missing) {
        if (!h.active()) return;
        String name = h.key().trim();
        headers.put(name.toLowerCase(Locale.ROOT), KeyValue.of(name, Templates.resolve(h.value(), lookup, missing).trim()));
    }

    static Function<String, String> lookup(Context ctx, Lane lane) {
        Scope scope = ctx.read().get(lane.name());
        Map<String, String> overrides = ctx.overrides();
        return name -> {
            String v = overrides.get(name);
            return v != null ? v : scope.get(name);
        };
    }

    // ---------------------------------------------------------------- extraction

    private static void extract(Step step, Lane lane, HttpExecutor.Reply reply, Context ctx,
                                Map<String, String> extracted, List<String> missed) {
        for (Extract e : step.extract()) {
            String value = ValuePaths.read(e.path(), reply.status(), reply.headers(), reply.binary() ? null : reply.body());
            if (value == null) {
                if (!lane.baseline()) missed.add(e.name() + " (" + e.path() + ")");
                continue;
            }
            Scope s = e.toSuite() ? ctx.suiteShared().get(lane.name())
                    : e.toFolder() ? ctx.shared().get(lane.name()) : ctx.read().get(lane.name());
            s.set(e.name(), value);
            if (!lane.baseline()) extracted.put(e.name(), value);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String unresolvedMessage(Set<String> names) {
        StringBuilder sb = new StringBuilder("Unresolved variable");
        sb.append(names.size() > 1 ? "s " : " ");
        sb.append(String.join(", ", names.stream().map(n -> "{{" + n + "}}").toList()));
        sb.append(" - no earlier step, setup, environment or suite variable set ")
                .append(names.size() > 1 ? "them" : "it");
        return sb.toString();
    }

    private static String laneLabel(Context ctx, Lane lane) {
        return ctx.lanes().size() > 1 ? "[" + lane.name() + "] " : "";
    }

    private static List<KeyValue> toList(Map<String, List<String>> headers) {
        List<KeyValue> out = new ArrayList<>();
        headers.forEach((k, vs) -> vs.forEach(v -> out.add(KeyValue.of(k, v))));
        return out;
    }

    static List<KeyValue> mask(List<KeyValue> headers) {
        List<KeyValue> out = new ArrayList<>(headers.size());
        for (KeyValue h : headers) {
            String k = h.key().toLowerCase(Locale.ROOT);
            if (k.equals("authorization") || k.equals("proxy-authorization") || k.contains("api-key") || k.contains("apikey")) {
                out.add(KeyValue.of(h.key(), maskValue(h.value())));
            } else {
                out.add(h);
            }
        }
        return out;
    }

    private static String maskValue(String v) {
        if (v == null || v.length() <= 12) return "****";
        int space = v.indexOf(' ');
        String scheme = space > 0 && space < 10 ? v.substring(0, space + 1) : "";
        String secret = v.substring(scheme.length());
        return scheme + (secret.length() > 8 ? secret.substring(0, 4) + "..." + secret.substring(secret.length() - 4) : "****");
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
