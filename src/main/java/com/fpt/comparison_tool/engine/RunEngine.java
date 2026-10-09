package com.fpt.comparison_tool.engine;

import com.fpt.comparison_tool.model.Defaults;
import com.fpt.comparison_tool.model.Environment;
import com.fpt.comparison_tool.model.Folder;
import com.fpt.comparison_tool.model.KeyValue;
import com.fpt.comparison_tool.model.Node;
import com.fpt.comparison_tool.model.Step;
import com.fpt.comparison_tool.model.Suite;
import com.fpt.comparison_tool.model.TestCase;
import com.fpt.comparison_tool.result.CaseResult;
import com.fpt.comparison_tool.result.Exchange;
import com.fpt.comparison_tool.result.HookResult;
import com.fpt.comparison_tool.result.RunHeader;
import com.fpt.comparison_tool.result.RunSummary;
import com.fpt.comparison_tool.result.Status;
import com.fpt.comparison_tool.result.StepResult;
import org.apache.hc.client5.http.cookie.CookieStore;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs a suite. Per folder: setup, then the test cases (in parallel up to the concurrency limit, or one by one),
 * then the sub-folders, then teardown. The steps of one test case always run in order with their own variables;
 * a test case waits for the setup of every folder above it and for the test cases it depends on.
 */
public final class RunEngine implements AutoCloseable {

    public static final String TOOL = "comparison-tool 2.0.0";
    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final HttpExecutor http;
    private final AuthService auth;
    private final StepRunner runner;

    public RunEngine() {
        this.http = new HttpExecutor();
        this.auth = new AuthService(http);
        this.runner = new StepRunner(http, auth);
    }

    /** A started run. */
    public record Handle(RunHeader header, RunControl control, CompletableFuture<RunSummary> done) {
    }

    /** Plans the run (errors are thrown here) and executes it on a background thread. */
    public Handle start(RunRequest request, RunListener listener) {
        Execution ex = new Execution(request, listener);
        CompletableFuture<RunSummary> done = new CompletableFuture<>();
        Thread t = Thread.ofPlatform().name("run-" + ex.header.runId()).daemon(true).unstarted(() -> {
            try {
                done.complete(ex.execute());
            } catch (Throwable e) {
                done.completeExceptionally(e);
            }
        });
        t.start();
        return new Handle(ex.header, ex.control, done);
    }

    /** Plans and executes the run on the calling thread. */
    public RunSummary run(RunRequest request, RunListener listener) {
        return new Execution(request, listener).execute();
    }

    /** Sends one step outside a run, for the editor's Send button. vars seed the variables the step can use. */
    public StepResult tryStep(Suite suite, String env, String baselineEnv, String folderId, Step step,
                              Map<String, String> vars) {
        List<Lane> lanes = lanes(suite, env, baselineEnv);
        Map<String, Scope> scopes = new LinkedHashMap<>();
        Map<String, CookieStore> cookies = new LinkedHashMap<>();
        for (Lane lane : lanes) {
            Scope s = rootScope(suite, lane).child("try");
            s.setAll(vars);
            scopes.put(lane.name(), s);
            cookies.put(lane.name(), HttpExecutor.newCookies());
        }
        List<Defaults> defaults = new ArrayList<>();
        defaults.add(suite.defaultsOrEmpty());
        List<Folder> chain = folderChain(suite.items(), folderId, new ArrayList<>());
        if (chain != null) chain.forEach(f -> defaults.add(f.defaultsOrEmpty()));
        StepRunner.Context ctx = new StepRunner.Context(suite, lanes, scopes, scopes, scopes, cookies, defaults, Map.of());
        return runner.run(step.disabled() == null ? step : withoutDisabled(step), "step", 0, ctx, new RunControl());
    }

    /** A step as it would be sent: method, URL, headers (secrets included) and body per lane, or the reason it cannot be. */
    public record Preview(String lane, String env, String method, String url, List<KeyValue> headers, String body, String error) {
    }

    public List<Preview> preview(Suite suite, String env, String baselineEnv, String folderId, Step step, Map<String, String> vars) {
        List<Lane> lanes = lanes(suite, env, baselineEnv);
        Map<String, Scope> scopes = new LinkedHashMap<>();
        for (Lane lane : lanes) {
            Scope s = rootScope(suite, lane).child("preview");
            s.setAll(vars);
            scopes.put(lane.name(), s);
        }
        List<Defaults> defaults = new ArrayList<>();
        defaults.add(suite.defaultsOrEmpty());
        List<Folder> chain = folderChain(suite.items(), folderId, new ArrayList<>());
        if (chain != null) chain.forEach(f -> defaults.add(f.defaultsOrEmpty()));
        StepRunner.Context ctx = new StepRunner.Context(suite, lanes, scopes, scopes, scopes, Map.of(), defaults, Map.of());
        List<Preview> out = new ArrayList<>();
        for (Lane lane : lanes) {
            StepRunner.Prepared p = runner.prepare(step, lane, ctx);
            String error = p.error() != null ? p.error()
                    : p.unresolved().isEmpty() ? null : "Unresolved: " + String.join(", ", p.unresolved());
            out.add(new Preview(lane.name(), lane.env().name(), p.method(), p.url(), p.headers(), p.body(), error));
        }
        return out;
    }

    public void clearAuthCache() {
        auth.clearCache();
    }

    @Override
    public void close() throws Exception {
        http.close();
    }

    // ---------------------------------------------------------------- one run

    private final class Execution {
        final RunRequest req;
        final Suite suite;
        final RunListener listener;
        final RunControl control = new RunControl();
        final Plan plan;
        final List<Lane> lanes;
        final RunHeader header;
        final Semaphore permits;
        final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        final Map<Status, Integer> counts = new EnumMap<>(Status.class);
        final long startedNanos = System.nanoTime();
        final int bodyLimit;
        final Map<String, Scope> suiteScopes = new LinkedHashMap<>();

        Execution(RunRequest req, RunListener listener) {
            this.req = req;
            this.suite = req.suite();
            this.listener = listener == null ? new RunListener() { } : listener;
            this.lanes = lanes(suite, req.env(), req.baselineEnv());
            this.plan = Plan.build(suite, req.selection());
            int concurrency = req.options().concurrency() != null ? req.options().concurrency()
                    : suite.settingsOrDefault().concurrencyOrDefault();
            this.permits = new Semaphore(Math.max(1, concurrency), true);
            this.bodyLimit = req.options().bodyLimitKb() * 1024;
            String runId = OffsetDateTime.now().format(RUN_ID) + "-" + Integer.toString(ThreadLocalRandom.current().nextInt(36 * 36 * 36), 36);
            Lane target = lanes.stream().filter(l -> !l.baseline()).findFirst().orElseThrow();
            Lane base = lanes.stream().filter(Lane::baseline).findFirst().orElse(null);
            this.header = new RunHeader(RunHeader.SCHEMA, runId, suite.name(), req.suiteFile(), target.env().name(),
                    base == null ? null : base.env().name(), now(), req.selection().describe(), concurrency,
                    req.options().capture(), req.options().hooks(), plan.size(), TOOL, plan.caseIds());
        }

        RunSummary execute() {
            auth.resetFailures();
            listener.started(header);
            String error = null;
            try {
                Map<String, Scope> roots = new LinkedHashMap<>();
                for (Lane lane : lanes) roots.put(lane.name(), rootScope(suite, lane));
                runFolder(plan.root, roots);
            } catch (Throwable e) {
                error = "Run aborted: " + e;
            } finally {
                pool.shutdownNow();
            }
            for (Plan.PCase pc : plan.cases) {
                if (!pc.done.isDone()) {
                    finishCase(pc, blankResult(pc, Status.ERROR, error != null ? error : "The test case did not run (internal error)", null));
                }
            }
            Map<String, Integer> c = new LinkedHashMap<>();
            for (Status s : Status.values()) c.put(s.value(), counts.getOrDefault(s, 0));
            RunSummary summary = new RunSummary(plan.size(), c, now(), (System.nanoTime() - startedNanos) / 1_000_000,
                    control.stopped() ? Boolean.TRUE : null, error);
            listener.finished(summary);
            return summary;
        }

        // ------------------------------------------------------------ folders and hooks

        void runFolder(Plan.PFolder pf, Map<String, Scope> parentScopes) {
            if (control.stopped()) {
                for (Plan.PCase pc : pf.allCases()) finishCase(pc, blankResult(pc, Status.SKIPPED, "Run stopped", null));
                return;
            }
            Map<String, Scope> scopes = new LinkedHashMap<>();
            for (Lane lane : lanes) {
                Scope parent = parentScopes.get(lane.name());
                scopes.put(lane.name(), pf.root() ? parent.child("suite") : parent.child("folder " + pf.id));
            }
            if (pf.root()) suiteScopes.putAll(scopes);
            String hooks = req.options().hooks();
            String blocked = null;
            if (RunOptions.HOOKS_REUSE.equals(hooks)) {
                Map<String, Map<String, String>> saved = req.options().reuse().get(pf.hookKey());
                if (saved != null) for (Lane lane : lanes) scopes.get(lane.name()).setAll(saved.get(lane.name()));
            }
            List<Step> setup = Plan.setupOf(suite, pf);
            if (RunOptions.HOOKS_RUN.equals(hooks) && anyActive(setup)) {
                blocked = runHook(pf, "setup", setup, scopes);
            }
            if (blocked != null) {
                for (Plan.PCase pc : pf.allCases()) finishCase(pc, blankResult(pc, Status.BLOCKED, blocked, null));
            } else {
                runCases(pf, scopes);
                if (pf.foldersParallel && pf.folders.size() > 1) {
                    List<CompletableFuture<Void>> futures = new ArrayList<>();
                    for (Plan.PFolder child : pf.folders) {
                        futures.add(CompletableFuture.runAsync(() -> guarded(() -> runFolder(child, scopes)), pool));
                    }
                    futures.forEach(CompletableFuture::join);
                } else {
                    for (Plan.PFolder child : pf.folders) runFolder(child, scopes);
                }
            }
            List<Step> teardown = Plan.teardownOf(suite, pf);
            if (RunOptions.HOOKS_RUN.equals(hooks) && anyActive(teardown) && !control.hardStopped()) {
                runHook(pf, "teardown", teardown, scopes);
            }
        }

        /** Runs a setup or teardown; returns the reason to block the folder's test cases, or null. */
        String runHook(Plan.PFolder pf, String hook, List<Step> steps, Map<String, Scope> scopes) {
            control.enter();
            try {
                String startedAt = now();
                long t0 = System.nanoTime();
                StepRunner.Context ctx = new StepRunner.Context(suite, lanes, scopes, scopes, suiteScopes, cookies(),
                        pf.defaults, req.options().variables());
                List<StepResult> results = new ArrayList<>();
                Status status = Status.PASS;
                String reason = null;
                String block = null;
                for (int i = 0; i < steps.size(); i++) {
                    Step step = steps.get(i);
                    if (!step.active()) continue;
                    boolean skip = control.hardStopped() || ("setup".equals(hook) && control.stopped());
                    StepResult r = skip ? StepResult.skipped(hook, i, step.name(), "Run stopped")
                            : runner.run(step, hook, i, ctx, control);
                    results.add(r);
                    Status s = r.statusEnum();
                    if (s == Status.SKIPPED) continue;
                    if (s != Status.PASS) {
                        if (reason == null) reason = "Step " + (i + 1) + " '" + step.name() + "': " + r.reason();
                        status = Status.worst(status, s);
                        if (step.optionsOrNone().requiredOn() && block == null) {
                            block = "Setup of " + where(pf) + " failed at required step " + (i + 1) + " '" + step.name() + "': " + r.reason();
                        }
                    }
                }
                Map<String, Map<String, String>> vars = new LinkedHashMap<>();
                if ("setup".equals(hook)) for (Lane lane : lanes) vars.put(lane.name(), scopes.get(lane.name()).local());
                boolean keepBodies = RunOptions.CAPTURE_ALL.equals(req.options().capture())
                        || (status != Status.PASS && !RunOptions.CAPTURE_NONE.equals(req.options().capture()));
                HookResult hr = new HookResult(pf.root() ? null : pf.id, pf.path, hook, status.value(), reason, startedAt,
                        (System.nanoTime() - t0) / 1_000_000, capture(results, keepBodies), vars);
                listener.hook(hr);
                return block;
            } finally {
                control.leave();
            }
        }

        // ------------------------------------------------------------ test cases

        void runCases(Plan.PFolder pf, Map<String, Scope> folderScopes) {
            if (pf.cases.isEmpty()) return;
            if (pf.casesParallel && pf.cases.size() > 1) {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (Plan.PCase pc : pf.cases) {
                    futures.add(CompletableFuture.runAsync(() -> guardedCase(pc, folderScopes), pool));
                }
                futures.forEach(CompletableFuture::join);
            } else {
                for (Plan.PCase pc : Plan.serialOrder(pf.cases)) guardedCase(pc, folderScopes);
            }
        }

        void guardedCase(Plan.PCase pc, Map<String, Scope> folderScopes) {
            try {
                finishCase(pc, runCase(pc, folderScopes));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                finishCase(pc, blankResult(pc, Status.SKIPPED, "Run stopped", null));
            } catch (Throwable e) {
                finishCase(pc, blankResult(pc, Status.ERROR, "Unexpected error: " + e, null));
            }
        }

        CaseResult runCase(Plan.PCase pc, Map<String, Scope> folderScopes) throws InterruptedException {
            if (control.stopped()) return blankResult(pc, Status.SKIPPED, "Run stopped", null);
            for (Plan.PCase dep : pc.deps) {
                CaseResult dr = dep.done.join();
                Status ds = dr.statusEnum();
                if (ds == Status.ERROR || ds == Status.BLOCKED || ds == Status.SKIPPED) {
                    return blankResult(pc, Status.BLOCKED, "Depends on " + dep.tc.label() + ", which ended " + ds.value(), null);
                }
            }
            permits.acquire();
            control.enter();
            try {
                if (control.stopped()) return blankResult(pc, Status.SKIPPED, "Run stopped", null);
                listener.caseStarted(pc.tc.id());
                String startedAt = now();
                long t0 = System.nanoTime();
                TestCase tc = pc.tc;
                Map<String, Scope> caseScopes = new LinkedHashMap<>();
                for (Lane lane : lanes) caseScopes.put(lane.name(), folderScopes.get(lane.name()).child("case " + tc.id()));
                StepRunner.Context ctx = new StepRunner.Context(suite, lanes, caseScopes, folderScopes, suiteScopes,
                        cookies(), pc.folder.defaults, req.options().variables());

                List<StepResult> results = new ArrayList<>();
                String blockReason = null;
                // a required precondition that does not pass blocks the test case; any other one makes it fail
                Status preStatus = null;
                String preReason = null;
                List<Step> pre = tc.preconditions();
                for (int i = 0; i < pre.size(); i++) {
                    Step step = pre.get(i);
                    if (!step.active()) continue;
                    if (blockReason != null || control.stopped()) {
                        results.add(StepResult.skipped("pre", i, step.name(), blockReason != null ? "Precondition failed" : "Run stopped"));
                        continue;
                    }
                    StepResult r = runner.run(step, "pre", i, ctx, control);
                    results.add(r);
                    Status ps = r.statusEnum();
                    if (ps != Status.PASS && ps != Status.SKIPPED) {
                        String why = "Precondition " + (i + 1) + " '" + step.name() + "': " + r.reason();
                        if (step.optionsOrNone().requiredOn()) {
                            blockReason = why;
                        } else {
                            preStatus = preStatus == null ? Status.FAIL : preStatus;
                            if (ps == Status.ERROR) preStatus = Status.worst(preStatus, Status.ERROR);
                            if (preReason == null) preReason = why;
                        }
                    }
                }

                Status status = null;
                String reason = null;
                String halt = blockReason != null ? "Precondition failed" : null;
                boolean anyRun = false;
                boolean stoppedMidway = false;
                List<Step> steps = tc.steps();
                for (int i = 0; i < steps.size(); i++) {
                    Step step = steps.get(i);
                    if (!step.active()) continue;
                    if (halt == null && control.stopped()) {
                        halt = "Run stopped";
                        stoppedMidway = true;
                    }
                    if (halt != null) {
                        results.add(StepResult.skipped("step", i, step.name(), halt));
                        continue;
                    }
                    StepResult r = runner.run(step, "step", i, ctx, control);
                    results.add(r);
                    anyRun = true;
                    Status s = r.statusEnum();
                    if (s != Status.PASS && s != Status.SKIPPED) {
                        status = status == null ? s : Status.worst(status, s);
                        if (reason == null) reason = "Step " + (i + 1) + " '" + step.name() + "': " + r.reason();
                        if (s == Status.ERROR) halt = "An earlier step ended with an error";
                        else if (tc.stopOnFailure() != null) halt = "Stopped on the first failure";
                    }
                }

                boolean cleanupFailed = false;
                List<Step> cleanup = tc.cleanup();
                for (int i = 0; i < cleanup.size(); i++) {
                    Step step = cleanup.get(i);
                    if (!step.active()) continue;
                    if (control.hardStopped()) {
                        results.add(StepResult.skipped("cleanup", i, step.name(), "Run stopped"));
                        continue;
                    }
                    StepResult r = runner.run(step, "cleanup", i, ctx, control);
                    results.add(r);
                    if (r.statusEnum() != Status.PASS) cleanupFailed = true;
                }

                if (blockReason == null && preStatus != null && !stoppedMidway) {
                    status = status == null ? preStatus : Status.worst(status, preStatus);
                    if (reason == null) reason = preReason;
                }
                if (blockReason != null) {
                    status = Status.BLOCKED;
                    reason = blockReason;
                } else if (status == null) {
                    if (stoppedMidway) {
                        status = Status.SKIPPED;
                        reason = "Run stopped before all steps ran";
                    } else if (!anyRun) {
                        status = Status.SKIPPED;
                        reason = "The test case has no enabled steps";
                    } else {
                        status = Status.PASS;
                    }
                }
                boolean keepBodies = RunOptions.CAPTURE_ALL.equals(req.options().capture())
                        || (status != Status.PASS && !RunOptions.CAPTURE_NONE.equals(req.options().capture()));
                return new CaseResult(tc.id(), tc.key(), tc.name(), pc.folder.root() ? null : pc.folder.id, pc.folder.path,
                        tc.tags(), status.value(), reason, cleanupFailed ? Boolean.TRUE : null, startedAt,
                        (System.nanoTime() - t0) / 1_000_000, capture(results, keepBodies), null);
            } finally {
                control.leave();
                permits.release();
            }
        }

        void finishCase(Plan.PCase pc, CaseResult result) {
            if (!pc.done.complete(result)) return;
            synchronized (counts) {
                counts.merge(result.statusEnum(), 1, Integer::sum);
            }
            listener.caseFinished(result);
        }

        CaseResult blankResult(Plan.PCase pc, Status status, String reason, List<StepResult> steps) {
            TestCase tc = pc.tc;
            return new CaseResult(tc.id(), tc.key(), tc.name(), pc.folder.root() ? null : pc.folder.id, pc.folder.path,
                    tc.tags(), status.value(), reason, null, now(), 0L, steps, null);
        }

        List<StepResult> capture(List<StepResult> results, boolean keepBodies) {
            List<StepResult> out = new ArrayList<>(results.size());
            for (StepResult r : results) {
                Exchange t = r.target();
                Exchange b = r.baseline();
                if (keepBodies) {
                    out.add(r.withExchanges(t == null ? null : t.truncate(bodyLimit), b == null ? null : b.truncate(bodyLimit)));
                } else {
                    out.add(r.withExchanges(t == null ? null : t.withoutBodies(), b == null ? null : b.withoutBodies()));
                }
            }
            return out;
        }

        Map<String, CookieStore> cookies() {
            Map<String, CookieStore> c = new LinkedHashMap<>();
            for (Lane lane : lanes) c.put(lane.name(), HttpExecutor.newCookies());
            return c;
        }

        void guarded(Runnable r) {
            try {
                r.run();
            } catch (Throwable e) {
                // a folder task failing must not hide the others; its cases are closed by execute()
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    static List<Lane> lanes(Suite suite, String env, String baselineEnv) {
        String targetName = env != null && !env.isBlank() ? env : suite.settingsOrDefault().env();
        if (targetName == null && !suite.environments().isEmpty()) targetName = suite.environments().get(0).name();
        Environment target = suite.environment(targetName);
        if (target == null) throw new Plan.PlanException("Unknown environment '" + targetName + "'");
        List<Lane> lanes = new ArrayList<>();
        if (baselineEnv != null && !baselineEnv.isBlank()) {
            Environment base = suite.environment(baselineEnv);
            if (base == null) throw new Plan.PlanException("Unknown baseline environment '" + baselineEnv + "'");
            lanes.add(new Lane(Lane.BASELINE, base));
        }
        lanes.add(new Lane(Lane.TARGET, target));
        return lanes;
    }

    /** Suite variables, overridden by the environment's variables ({{baseUrl}} = the environment URL). */
    static Scope rootScope(Suite suite, Lane lane) {
        Scope root = new Scope(null, "suite variables");
        for (KeyValue v : suite.variables()) if (v.active()) root.set(bare(v.key()), v.value());
        Scope env = root.child("environment " + lane.env().name());
        if (lane.env().baseUrl() != null) env.set("baseUrl", lane.env().baseUrl());
        for (KeyValue v : lane.env().variables()) if (v.active()) env.set(bare(v.key()), v.value());
        return env;
    }

    static String bare(String name) {
        String n = name.trim();
        if (n.startsWith("{{") && n.endsWith("}}")) n = n.substring(2, n.length() - 2);
        return n.trim();
    }

    private static boolean anyActive(List<Step> steps) {
        return steps.stream().anyMatch(Step::active);
    }

    private static String where(Plan.PFolder pf) {
        return pf.root() ? "the suite" : "folder '" + String.join("/", pf.path) + "'";
    }

    private static List<Folder> folderChain(List<Node> items, String folderId, List<Folder> chain) {
        if (folderId == null || folderId.isBlank()) return null;
        for (Node n : items) {
            if (n instanceof Folder f) {
                List<Folder> next = new ArrayList<>(chain);
                next.add(f);
                if (f.id().equals(folderId)) return next;
                List<Folder> found = folderChain(f.items(), folderId, next);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Step withoutDisabled(Step s) {
        return new Step(s.name(), null, s.method(), s.url(), s.query(), s.headers(), s.body(), s.auth(), s.extract(),
                s.expect(), s.options(), s.notes());
    }

    static String now() {
        return OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
