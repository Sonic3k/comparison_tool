package com.fpt.comparison_tool.cli;

import com.fpt.comparison_tool.engine.Plan;
import com.fpt.comparison_tool.engine.RunEngine;
import com.fpt.comparison_tool.engine.RunListener;
import com.fpt.comparison_tool.engine.RunOptions;
import com.fpt.comparison_tool.engine.RunRequest;
import com.fpt.comparison_tool.engine.Selection;
import com.fpt.comparison_tool.io.BrunoExport;
import com.fpt.comparison_tool.io.ExcelSuite;
import com.fpt.comparison_tool.io.PostmanExport;
import com.fpt.comparison_tool.io.SuiteFiles;
import com.fpt.comparison_tool.model.Suite;
import com.fpt.comparison_tool.report.ExcelReport;
import com.fpt.comparison_tool.report.JUnitReport;
import com.fpt.comparison_tool.result.CaseResult;
import com.fpt.comparison_tool.result.HookResult;
import com.fpt.comparison_tool.result.RunData;
import com.fpt.comparison_tool.result.RunFile;
import com.fpt.comparison_tool.result.RunHeader;
import com.fpt.comparison_tool.result.RunSummary;
import com.fpt.comparison_tool.result.Status;
import com.fpt.comparison_tool.suite.SuiteJson;
import com.fpt.comparison_tool.suite.SuiteValidator;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Command line mode: run, convert, validate, report and export without starting the web UI. */
public final class Cli {

    public static final int OK = 0;
    public static final int NOT_PASSED = 1;
    public static final int FAILURE = 2;

    private static final List<String> COMMANDS = List.of("run", "convert", "validate", "report", "export", "help", "--help", "-h");

    private final PrintStream out;
    private final PrintStream err;

    public Cli(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static boolean isCommand(String arg) {
        return arg != null && COMMANDS.contains(arg.toLowerCase(Locale.ROOT));
    }

    public int execute(String[] args) {
        String cmd = args[0].toLowerCase(Locale.ROOT);
        Args a = new Args(args);
        try {
            return switch (cmd) {
                case "run" -> run(a);
                case "convert" -> convert(a);
                case "validate" -> validate(a);
                case "report" -> report(a);
                case "export" -> export(a);
                default -> help();
            };
        } catch (Plan.PlanException | IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
            return FAILURE;
        } catch (IOException e) {
            err.println("I/O error: " + e.getMessage());
            return FAILURE;
        }
    }

    // ---------------------------------------------------------------- run

    private int run(Args a) throws IOException {
        Path suitePath = a.file(0, "suite file");
        SuiteJson.Loaded loaded = SuiteFiles.read(suitePath);
        Suite suite = loaded.suite();
        if (!loaded.notes().isEmpty() && !a.flag("quiet")) {
            out.println("Converted from " + loaded.format() + " format:");
            loaded.notes().forEach(n -> out.println("  " + n));
        }
        Selection sel = new Selection(null, a.all("case"), null, a.all("folder"), a.all("tag"));
        Map<String, String> vars = new LinkedHashMap<>();
        for (String v : a.all("var")) {
            int eq = v.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("--var expects name=value, got: " + v);
            vars.put(v.substring(0, eq).trim(), v.substring(eq + 1));
        }
        RunOptions opts = new RunOptions(a.intValue("concurrency"), a.value("capture", RunOptions.CAPTURE_FAILED),
                a.intValue("body-limit-kb"), null, null, vars);
        RunRequest req = new RunRequest(suite, a.value("env", null), a.value("baseline", null), sel, opts,
                suitePath.toAbsolutePath().toString());

        Path outDir = a.value("out", null) != null ? Paths.get(a.value("out", null))
                : suitePath.toAbsolutePath().getParent().resolve("results");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String base = baseName(suitePath);
        boolean quiet = a.flag("quiet");

        List<CaseResult> cases = new ArrayList<>();
        List<HookResult> hooks = new ArrayList<>();
        RunHeader[] header = new RunHeader[1];
        AtomicInteger done = new AtomicInteger();
        RunListener console = new RunListener() {
            @Override
            public void started(RunHeader h) {
                header[0] = h;
                out.printf("Run %s - %s - env %s%s - %d test case(s)%n", h.runId(), h.suite(), h.env(),
                        h.baselineEnv() == null ? "" : " vs " + h.baselineEnv(), h.planned());
            }

            @Override
            public void hook(HookResult h) {
                synchronized (hooks) {
                    hooks.add(h);
                }
                if (quiet && h.statusEnum() == Status.PASS) return;
                String where = h.path() == null || h.path().isEmpty() ? "suite" : String.join("/", h.path());
                out.printf("  %-8s %s %s%s%n", h.status().toUpperCase(Locale.ROOT), h.hook(), where,
                        h.reason() == null ? "" : " - " + h.reason());
            }

            @Override
            public void caseFinished(CaseResult c) {
                synchronized (cases) {
                    cases.add(c);
                }
                int n = done.incrementAndGet();
                if (quiet && c.statusEnum() == Status.PASS) return;
                out.printf("[%4d/%d] %-7s %s%s%n", n, header[0].planned(), c.status().toUpperCase(Locale.ROOT),
                        label(c), c.reason() == null ? "" : " - " + c.reason());
            }
        };

        Path runFile;
        RunSummary summary;
        try (RunEngine engine = new RunEngine()) {
            runFile = null;
            RunFile.Writer[] writer = new RunFile.Writer[1];
            RunListener fileListener = new RunListener() {
                @Override
                public void started(RunHeader h) {
                    try {
                        Path f = outDir.resolve(base + "_" + h.env() + "_" + stamp + ".run.jsonl");
                        writer[0] = new RunFile.Writer(f);
                        writer[0].started(h);
                    } catch (IOException e) {
                        err.println("Cannot write the run file: " + e.getMessage());
                    }
                }

                @Override
                public void hook(HookResult r) {
                    if (writer[0] != null) writer[0].hook(r);
                }

                @Override
                public void caseFinished(CaseResult r) {
                    if (writer[0] != null) writer[0].caseFinished(r);
                }

                @Override
                public void finished(RunSummary s) {
                    if (writer[0] != null) writer[0].finished(s);
                }
            };
            summary = engine.run(req, RunListener.of(List.of(fileListener, console)));
            if (writer[0] != null) writer[0].close();
            runFile = outDir.resolve(base + "_" + header[0].env() + "_" + stamp + ".run.jsonl");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new IOException(e.getMessage(), e);
        }

        RunData data = new RunData(header[0], hooks, cases, summary);
        out.println();
        out.printf("Total %d: %d pass, %d fail, %d error, %d blocked, %d skipped in %.1fs%n", summary.total(),
                summary.count(Status.PASS), summary.count(Status.FAIL), summary.count(Status.ERROR),
                summary.count(Status.BLOCKED), summary.count(Status.SKIPPED), summary.durationMs() / 1000.0);
        out.println("Run file: " + runFile);
        if (a.flag("junit")) {
            Path f = outDir.resolve(base + "_" + header[0].env() + "_" + stamp + ".junit.xml");
            try (OutputStream os = Files.newOutputStream(f)) {
                JUnitReport.write(data, os);
            }
            out.println("JUnit:    " + f);
        }
        if (a.flag("excel")) {
            Path f = outDir.resolve(base + "_" + header[0].env() + "_" + stamp + ".report.xlsx");
            try (OutputStream os = Files.newOutputStream(f)) {
                ExcelReport.write(data, os);
            }
            out.println("Excel:    " + f);
        }
        return summary.count(Status.PASS) == summary.total() ? OK : NOT_PASSED;
    }

    // ---------------------------------------------------------------- other commands

    private int convert(Args a) throws IOException {
        Path in = a.file(0, "1.x suite file");
        SuiteJson.Loaded loaded = SuiteFiles.read(in);
        Path target = a.value("out", null) != null ? Paths.get(a.value("out", null))
                : in.toAbsolutePath().resolveSibling(baseName(in) + ".suite.json");
        SuiteJson.save(loaded.suite(), target);
        out.println("Read " + loaded.format() + " suite, wrote " + target);
        loaded.notes().forEach(n -> out.println("  " + n));
        return OK;
    }

    private int validate(Args a) throws IOException {
        SuiteJson.Loaded loaded = SuiteFiles.read(a.file(0, "suite file"));
        List<SuiteValidator.Issue> issues = SuiteValidator.validate(loaded.suite());
        for (SuiteValidator.Issue i : issues) out.printf("%-7s %s: %s%n", i.level().toUpperCase(Locale.ROOT), i.where(), i.message());
        long errors = issues.stream().filter(i -> i.level().equals("error")).count();
        out.println(issues.isEmpty() ? "No problems found" : errors + " error(s), " + (issues.size() - errors) + " warning(s)");
        return errors == 0 ? OK : NOT_PASSED;
    }

    private int report(Args a) throws IOException {
        Path in = a.file(0, "run file");
        RunData data = RunFile.read(in);
        boolean any = false;
        if (a.value("excel", null) != null) {
            try (OutputStream os = Files.newOutputStream(Paths.get(a.value("excel", null)))) {
                ExcelReport.write(data, os);
            }
            out.println("Excel: " + a.value("excel", null));
            any = true;
        }
        if (a.value("junit", null) != null) {
            try (OutputStream os = Files.newOutputStream(Paths.get(a.value("junit", null)))) {
                JUnitReport.write(data, os);
            }
            out.println("JUnit: " + a.value("junit", null));
            any = true;
        }
        if (!any) {
            Map<String, Integer> c = data.counts();
            out.println(data.header().suite() + " - " + data.header().env() + " - " + data.cases().size() + " test case(s): " + c);
            for (CaseResult r : data.notPassed()) {
                out.printf("  %-7s %s%s%n", r.status().toUpperCase(Locale.ROOT), label(r), r.reason() == null ? "" : " - " + r.reason());
            }
        }
        return OK;
    }

    private int export(Args a) throws IOException {
        Path in = a.file(0, "suite file");
        Suite suite = SuiteFiles.read(in).suite();
        String format = a.value("format", "json").toLowerCase(Locale.ROOT);
        String ext = switch (format) {
            case "excel", "xlsx" -> ".xlsx";
            case "postman" -> ".postman_collection.json";
            case "bruno" -> ".bruno.zip";
            case "json" -> ".suite.json";
            default -> throw new IllegalArgumentException("Unknown format '" + format + "' (excel, postman, bruno, json)");
        };
        Path target = a.value("out", null) != null ? Paths.get(a.value("out", null))
                : in.toAbsolutePath().resolveSibling(baseName(in) + ext);
        try (OutputStream os = Files.newOutputStream(target)) {
            switch (format) {
                case "excel", "xlsx" -> ExcelSuite.write(suite, os);
                case "postman" -> os.write(PostmanExport.export(suite, a.value("env", null)).getBytes(StandardCharsets.UTF_8));
                case "bruno" -> BrunoExport.export(suite, os);
                default -> os.write(SuiteJson.write(suite).getBytes(StandardCharsets.UTF_8));
            }
        }
        out.println("Wrote " + target);
        return OK;
    }

    private int help() {
        out.println("""
                API Comparison Tools 2

                  java -jar comparison-tool.jar                     start the UI on http://localhost:8080
                  java -jar comparison-tool.jar <suite.json>        start the UI with this suite open (Save writes back,
                                                                    run results go to results/ next to the suite)
                      --port N             another port than 8080
                      --no-browser         do not open the browser (it opens by itself on Windows and macOS)

                  java -jar comparison-tool.jar run <suite> [options]
                      --env NAME           environment under test (default: the suite setting, else the first)
                      --baseline NAME      also call this environment and diff every step
                      --case KEY           only this test case (repeatable); --folder PATH, --tag TAG likewise
                      --concurrency N      test cases running at the same time
                      --var name=value     override a variable (repeatable)
                      --capture MODE       failed (default) | all | none - which response bodies the run file keeps
                      --out DIR            where to write results (default: results/ next to the suite)
                      --junit --excel      also write a JUnit XML / Excel report
                      --quiet              print only test cases that did not pass
                  java -jar comparison-tool.jar convert <old-suite.json> [--out FILE]
                  java -jar comparison-tool.jar validate <suite>
                  java -jar comparison-tool.jar report <run.jsonl> [--excel FILE] [--junit FILE]
                  java -jar comparison-tool.jar export <suite> --format excel|postman|bruno|json [--env NAME] [--out FILE]

                Exit code of run: 0 all passed, 1 some did not pass, 2 the run could not start.
                """);
        return OK;
    }

    // ---------------------------------------------------------------- helpers

    private static String label(CaseResult c) {
        String path = c.path() == null || c.path().isEmpty() ? "" : String.join("/", c.path()) + " :: ";
        String name = c.key() == null || c.key().isBlank() ? c.name() : c.key() + (c.name() == null || c.name().equals(c.key()) ? "" : " " + c.name());
        return path + name;
    }

    static String baseName(Path p) {
        String n = p.getFileName().toString();
        for (String ext : List.of(".suite.json", ".json", ".xlsx")) {
            if (n.toLowerCase(Locale.ROOT).endsWith(ext)) return n.substring(0, n.length() - ext.length());
        }
        return n;
    }

    /** Parsed arguments: positional values and --name value / --flag options (repeatable). */
    static final class Args {
        final List<String> positional = new ArrayList<>();
        final Map<String, List<String>> options = new LinkedHashMap<>();
        /** Options without a value; --junit and --excel take a file name everywhere except in run (there: --junit=FILE). */
        private static final List<String> FLAGS = List.of("junit", "excel", "quiet");

        Args(String[] args) {
            for (int i = 1; i < args.length; i++) {
                String s = args[i];
                if (s.startsWith("--")) {
                    String name = s.substring(2);
                    String value = null;
                    int eq = name.indexOf('=');
                    if (eq > 0) {
                        value = name.substring(eq + 1);
                        name = name.substring(0, eq);
                    } else if (!isFlag(name, args[0]) && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        value = args[++i];
                    }
                    options.computeIfAbsent(name, k -> new ArrayList<>()).add(value == null ? "true" : value);
                } else {
                    positional.add(s);
                }
            }
        }

        private static boolean isFlag(String name, String command) {
            return name.equals("quiet") || (FLAGS.contains(name) && "run".equals(command));
        }

        Path file(int index, String what) {
            if (positional.size() <= index) throw new IllegalArgumentException("Missing " + what);
            Path p = Paths.get(positional.get(index));
            if (!Files.isRegularFile(p)) throw new IllegalArgumentException(what + " not found: " + p);
            return p;
        }

        String value(String name, String def) {
            List<String> v = options.get(name);
            return v == null || v.isEmpty() ? def : v.get(v.size() - 1);
        }

        Integer intValue(String name) {
            String v = value(name, null);
            if (v == null) return null;
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--" + name + " expects a number, got: " + v);
            }
        }

        List<String> all(String name) {
            return options.getOrDefault(name, List.of());
        }

        boolean flag(String name) {
            return options.containsKey(name);
        }
    }
}
