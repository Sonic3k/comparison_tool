package com.fpt.comparison_tool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.comparison_tool.engine.Plan;
import com.fpt.comparison_tool.engine.RunEngine;
import com.fpt.comparison_tool.engine.RunListener;
import com.fpt.comparison_tool.engine.RunOptions;
import com.fpt.comparison_tool.engine.RunRequest;
import com.fpt.comparison_tool.engine.Selection;
import com.fpt.comparison_tool.io.BrunoExport;
import com.fpt.comparison_tool.io.Curl;
import com.fpt.comparison_tool.io.ExcelSuite;
import com.fpt.comparison_tool.io.PostmanExport;
import com.fpt.comparison_tool.io.SuiteFiles;
import com.fpt.comparison_tool.model.Step;
import com.fpt.comparison_tool.model.Suite;
import com.fpt.comparison_tool.report.ExcelReport;
import com.fpt.comparison_tool.report.JUnitReport;
import com.fpt.comparison_tool.result.RunData;
import com.fpt.comparison_tool.result.RunFile;
import com.fpt.comparison_tool.result.Status;
import com.fpt.comparison_tool.result.StepResult;
import com.fpt.comparison_tool.suite.Json;
import com.fpt.comparison_tool.suite.SuiteJson;
import com.fpt.comparison_tool.suite.SuiteValidator;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** REST API used by the web UI. Suites travel with each request; the server keeps only recent runs in memory. */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final RunEngine engine;
    private final RunStore store;

    public ApiController(RunEngine engine, RunStore store) {
        this.engine = engine;
        this.store = store;
    }

    // ---------------------------------------------------------------- info and suite files

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tool", RunEngine.TOOL);
        String file = System.getProperty("ct.startup-file");
        if (file != null) {
            Path p = Paths.get(file);
            m.put("file", Map.of("name", p.getFileName().toString(), "path", p.toString(), "resultsDir", resultsDir(p).toString()));
        }
        return m;
    }

    @GetMapping("/startup-suite")
    public ResponseEntity<?> startupSuite() throws IOException {
        String file = System.getProperty("ct.startup-file");
        if (file == null) return ResponseEntity.noContent().build();
        SuiteJson.Loaded loaded = SuiteFiles.read(Paths.get(file));
        return ResponseEntity.ok(opened(loaded, Paths.get(file).getFileName().toString()));
    }

    @PostMapping("/startup-suite")
    public Map<String, Object> saveStartupSuite(@RequestBody Suite suite) throws IOException {
        String file = System.getProperty("ct.startup-file");
        if (file == null) throw new IllegalArgumentException("The tool was not started with a suite file");
        Path p = Paths.get(file);
        if (p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            try (var os = Files.newOutputStream(p)) {
                ExcelSuite.write(suite, os);
            }
        } else {
            SuiteJson.save(suite, p);
        }
        return Map.of("savedAt", OffsetDateTime.now().toString(), "path", p.toString());
    }

    @PostMapping(value = "/suite/open", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> open(@RequestParam("file") MultipartFile file) throws IOException {
        SuiteJson.Loaded loaded = SuiteFiles.read(file.getOriginalFilename(), file.getBytes());
        return opened(loaded, file.getOriginalFilename());
    }

    @PostMapping("/suite/validate")
    public List<SuiteValidator.Issue> validate(@RequestBody Suite suite) {
        return SuiteValidator.validate(suite);
    }

    @PostMapping("/suite/export")
    public ResponseEntity<byte[]> export(@RequestBody Suite suite, @RequestParam String format,
                                         @RequestParam(required = false) String env) throws IOException {
        String base = safeName(suite.name());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String name;
        MediaType type;
        switch (format.toLowerCase(Locale.ROOT)) {
            case "excel", "xlsx" -> {
                ExcelSuite.write(suite, out);
                name = base + ".xlsx";
                type = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            }
            case "postman" -> {
                out.write(PostmanExport.export(suite, env).getBytes(StandardCharsets.UTF_8));
                name = base + ".postman_collection.json";
                type = MediaType.APPLICATION_JSON;
            }
            case "bruno" -> {
                BrunoExport.export(suite, out);
                name = base + ".bruno.zip";
                type = MediaType.parseMediaType("application/zip");
            }
            default -> {
                out.write(SuiteJson.write(suite).getBytes(StandardCharsets.UTF_8));
                name = base + ".suite.json";
                type = MediaType.APPLICATION_JSON;
            }
        }
        return download(out.toByteArray(), name, type);
    }

    // ---------------------------------------------------------------- runs

    /**
     * Body of POST /runs. persist false keeps the run in memory only: the UI uses it to run test cases again inside
     * an earlier run, whose result file it then saves itself.
     */
    public record StartRun(Suite suite, String env, String baselineEnv, Selection selection, RunOptions options,
                           Boolean persist) {
    }

    @PostMapping("/runs")
    public Map<String, Object> start(@RequestBody StartRun body) {
        RunStore.Live live = store.create(null);
        List<RunListener> listeners = new ArrayList<>();
        listeners.add(live);
        RunOptions opts = body.options() == null ? new RunOptions(null, RunOptions.CAPTURE_ALL, null, null, null, null) : body.options();
        String file = System.getProperty("ct.startup-file");
        RunFile.Writer writer = null;
        if (file != null && !Boolean.FALSE.equals(body.persist())) {
            try {
                Path p = Paths.get(file);
                String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                String envName = body.env() == null ? "run" : body.env();
                Path out = resultsDir(p).resolve(base(p) + "_" + safeName(envName) + "_" + stamp + ".run.jsonl");
                writer = new RunFile.Writer(out);
                live.resultFile = out.toString();
                listeners.add(writer);
            } catch (IOException e) {
                live.resultFile = null;
            }
        }
        RunFile.Writer w = writer;
        listeners.add(new RunListener() {
            @Override
            public void finished(com.fpt.comparison_tool.result.RunSummary s) {
                if (w != null) {
                    try {
                        w.close();
                    } catch (IOException ignored) {
                        // file already flushed line by line
                    }
                }
            }
        });
        RunRequest req = new RunRequest(body.suite(), body.env(), body.baselineEnv(), body.selection(), opts, file);
        RunEngine.Handle h = engine.start(req, RunListener.of(listeners));
        live.header = h.header();
        live.control = h.control();
        store.register(live);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", h.header().runId());
        m.put("header", h.header());
        if (live.resultFile != null) m.put("resultFile", live.resultFile);
        return m;
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> runs() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RunStore.Live l : store.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", l.runId == null ? l.header.runId() : l.runId);
            m.put("header", l.header);
            m.put("done", l.done());
            if (l.summary != null) m.put("summary", l.summary);
            m.put("finished", l.cases.size());
            out.add(m);
        }
        return out;
    }

    @GetMapping("/runs/{id}/events")
    public Map<String, Object> events(@PathVariable String id, @RequestParam(defaultValue = "0") int after) {
        RunStore.Live l = live(id);
        List<RunStore.Event> events = l.after(after);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("events", events);
        m.put("next", events.isEmpty() ? after : events.get(events.size() - 1).seq());
        m.put("done", l.done() && l.after(after + events.size()).isEmpty());
        return m;
    }

    @PostMapping("/runs/{id}/stop")
    public Map<String, Object> stop(@PathVariable String id) {
        RunStore.Live l = live(id);
        if (l.control != null) l.control.stop();
        return Map.of("stopping", true, "hard", l.control != null && l.control.hardStopped());
    }

    @GetMapping("/runs/{id}/file")
    public ResponseEntity<byte[]> runFile(@PathVariable String id) throws IOException {
        RunStore.Live l = live(id);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RunFile.write(l.data(), out);
        return download(out.toByteArray(), safeName(l.header.suite()) + "_" + safeName(l.header.env()) + "_" + l.header.runId()
                + ".run.jsonl", MediaType.parseMediaType("application/x-ndjson"));
    }

    /** Result files in results/ next to the startup suite, newest first (server-file mode). */
    @GetMapping("/results")
    public List<Map<String, Object>> results() throws IOException {
        String file = System.getProperty("ct.startup-file");
        if (file == null) return List.of();
        Path dir = resultsDir(Paths.get(file));
        if (!Files.isDirectory(dir)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path p : stream.filter(x -> x.getFileName().toString().endsWith(".jsonl")).toList()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p.getFileName().toString());
                m.put("size", Files.size(p));
                m.put("modified", Files.getLastModifiedTime(p).toInstant().toString());
                out.add(m);
            }
        }
        out.sort((a, b) -> ((String) b.get("modified")).compareTo((String) a.get("modified")));
        return out;
    }

    @GetMapping("/results/{name}")
    public ResponseEntity<byte[]> result(@PathVariable String name) throws IOException {
        String file = System.getProperty("ct.startup-file");
        if (file == null || !name.matches("[\\w.\\-]+\\.jsonl")) throw new NotFound("No such result file");
        Path p = resultsDir(Paths.get(file)).resolve(name);
        if (!Files.isRegularFile(p)) throw new NotFound("No such result file: " + name);
        return download(Files.readAllBytes(p), name, MediaType.parseMediaType("application/x-ndjson"));
    }

    /**
     * Writes a result file in results/ (server-file mode): the run as the UI holds it, with labels, notes and the
     * test cases run again inside it. The text must be a run file; the name is a plain file name.
     */
    @PostMapping(value = "/results/{name}", consumes = {MediaType.TEXT_PLAIN_VALUE, "application/x-ndjson"})
    public Map<String, Object> saveResult(@PathVariable String name, @RequestBody String text) throws IOException {
        String file = System.getProperty("ct.startup-file");
        if (file == null) throw new IllegalArgumentException("The tool was not started with a suite file, so it has no results folder");
        if (!name.matches("[\\w.\\-]+\\.jsonl")) throw new IllegalArgumentException("Not a result file name: " + name);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        RunFile.read(new ByteArrayInputStream(bytes));
        Path dir = resultsDir(Paths.get(file));
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        statusCache.remove(p.toString());
        return Map.of("path", p.toString(), "name", name);
    }

    private record Statuses(long size, long modified, Map<String, Object> summary) {
    }

    private final Map<String, Statuses> statusCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The outcome of every test case in recent result files (server-file mode), newest first, optionally only runs
     * of one suite: for "compared with" and the recent-runs dots, without loading whole files in the browser.
     */
    @GetMapping("/results/statuses")
    public List<Map<String, Object>> statuses(@RequestParam(required = false) String suite,
                                              @RequestParam(defaultValue = "12") int limit) throws IOException {
        String file = System.getProperty("ct.startup-file");
        if (file == null) return List.of();
        Path dir = resultsDir(Paths.get(file));
        if (!Files.isDirectory(dir)) return List.of();
        List<Path> files;
        try (var stream = Files.list(dir)) {
            files = new ArrayList<>(stream.filter(x -> x.getFileName().toString().endsWith(".jsonl")).toList());
        }
        files.sort((a, b) -> Long.compare(b.toFile().lastModified(), a.toFile().lastModified()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Path p : files) {
            if (out.size() >= Math.max(1, limit)) break;
            Map<String, Object> m;
            try {
                m = statusesOf(p);
            } catch (IOException | RuntimeException e) {
                continue;
            }
            if (m == null || (suite != null && !suite.equals(m.get("suite")))) continue;
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> statusesOf(Path p) throws IOException {
        long size = Files.size(p);
        long modified = Files.getLastModifiedTime(p).toMillis();
        Statuses cached = statusCache.get(p.toString());
        if (cached != null && cached.size == size && cached.modified == modified) return cached.summary;
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, String> statuses = new LinkedHashMap<>();
        try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line;
            boolean header = false;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode n = Json.MAPPER.readTree(line);
                String kind = n.path("kind").asText("");
                if (kind.equals("header")) {
                    header = true;
                    m.put("name", p.getFileName().toString());
                    for (String f : List.of("runId", "suite", "env", "baselineEnv", "startedAt", "selection")) {
                        if (n.hasNonNull(f)) m.put(f, n.get(f).asText());
                    }
                } else if (kind.equals("case") && n.hasNonNull("caseId")) {
                    statuses.put(n.get("caseId").asText(), n.path("status").asText("skipped"));
                }
            }
            if (!header) return null;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String st : statuses.values()) counts.merge(st, 1, Integer::sum);
        m.put("counts", counts);
        m.put("statuses", statuses);
        statusCache.put(p.toString(), new Statuses(size, modified, m));
        return m;
    }

    /** Writes a run back to its result file (server-file mode), for example after triage labels were added. */
    @PostMapping("/runs/{id}/save")
    public Map<String, Object> saveRun(@PathVariable String id, @RequestBody RunData data) throws IOException {
        RunStore.Live l = live(id);
        if (l.resultFile == null) throw new IllegalArgumentException("This run has no result file on the server");
        if (!l.done()) throw new IllegalArgumentException("The run is still going; save it when it has finished");
        Path p = Paths.get(l.resultFile);
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        try (var os = Files.newOutputStream(tmp)) {
            RunFile.write(data, os);
        }
        try {
            Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return Map.of("path", p.toString());
    }

    @PostMapping("/report")
    public ResponseEntity<byte[]> report(@RequestBody RunData data, @RequestParam String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String base = safeName(data.header().suite()) + "_" + safeName(data.header().env()) + "_" + data.header().runId();
        if ("junit".equalsIgnoreCase(format)) {
            JUnitReport.write(data, out);
            return download(out.toByteArray(), base + ".junit.xml", MediaType.APPLICATION_XML);
        }
        ExcelReport.write(data, out);
        return download(out.toByteArray(), base + ".report.xlsx",
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    // ---------------------------------------------------------------- single step

    /** Body of POST /try and /curl/build. */
    public record TryStep(Suite suite, String env, String baselineEnv, String folderId, Step step, Map<String, String> vars) {
    }

    @PostMapping("/try")
    public StepResult tryStep(@RequestBody TryStep body) {
        return engine.tryStep(body.suite(), body.env(), body.baselineEnv(), body.folderId(), body.step(),
                body.vars() == null ? Map.of() : body.vars());
    }

    @PostMapping("/curl/build")
    public List<Map<String, Object>> curl(@RequestBody TryStep body) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RunEngine.Preview p : engine.preview(body.suite(), body.env(), body.baselineEnv(), body.folderId(), body.step(),
                body.vars() == null ? Map.of() : body.vars())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lane", p.lane());
            m.put("env", p.env());
            m.put("url", p.url());
            if (p.error() != null) m.put("error", p.error());
            m.put("curl", Curl.build(p.method(), p.url(), p.headers(), p.body()));
            out.add(m);
        }
        return out;
    }

    @PostMapping("/curl/parse")
    public Step parseCurl(@RequestBody Map<String, String> body) {
        return Curl.parse(body.getOrDefault("text", ""));
    }

    // ---------------------------------------------------------------- CI

    @PostMapping(value = "/ci/run", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<byte[]> ci(@RequestBody JsonNode suiteJson, @RequestParam(required = false) String env,
                                     @RequestParam(required = false) String baseline,
                                     @RequestParam(required = false) List<String> caseKey,
                                     @RequestParam(required = false) List<String> folder,
                                     @RequestParam(required = false) List<String> tag,
                                     @RequestHeader(value = "X-API-Key", required = false) String key) throws IOException {
        String expected = System.getenv("CT_CI_API_KEY");
        if (expected != null && !expected.isBlank() && !expected.equals(key)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("<error>X-API-Key missing or wrong</error>".getBytes(StandardCharsets.UTF_8));
        }
        Suite suite = SuiteJson.parse(suiteJson).suite();
        List<com.fpt.comparison_tool.result.CaseResult> cases = new ArrayList<>();
        List<com.fpt.comparison_tool.result.HookResult> hooks = new ArrayList<>();
        com.fpt.comparison_tool.result.RunHeader[] header = new com.fpt.comparison_tool.result.RunHeader[1];
        com.fpt.comparison_tool.result.RunSummary summary = engine.run(new RunRequest(suite, env, baseline,
                new Selection(null, caseKey, null, folder, tag), RunOptions.defaults(), null), new RunListener() {
            @Override
            public void started(com.fpt.comparison_tool.result.RunHeader h) {
                header[0] = h;
            }

            @Override
            public synchronized void hook(com.fpt.comparison_tool.result.HookResult r) {
                hooks.add(r);
            }

            @Override
            public synchronized void caseFinished(com.fpt.comparison_tool.result.CaseResult r) {
                cases.add(r);
            }
        });
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JUnitReport.write(new RunData(header[0], hooks, cases, summary), out);
        return ResponseEntity.status(summary.count(Status.PASS) == summary.total() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_XML).body(out.toByteArray());
    }

    // ---------------------------------------------------------------- helpers

    @ExceptionHandler({IllegalArgumentException.class, Plan.PlanException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? e.toString() : e.getMessage()));
    }

    @ExceptionHandler(NotFound.class)
    public ResponseEntity<Map<String, String>> notFound(NotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    static final class NotFound extends RuntimeException {
        NotFound(String m) {
            super(m);
        }
    }

    private RunStore.Live live(String id) {
        RunStore.Live l = store.get(id);
        if (l == null) throw new NotFound("Run " + id + " is not in memory any more (the server keeps the last few runs)");
        return l;
    }

    private static Map<String, Object> opened(SuiteJson.Loaded loaded, String fileName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("suite", loaded.suite());
        m.put("format", loaded.format());
        m.put("notes", loaded.notes());
        m.put("fileName", fileName);
        m.put("issues", SuiteValidator.validate(loaded.suite()));
        return m;
    }

    private static ResponseEntity<byte[]> download(byte[] bytes, String name, MediaType type) {
        HttpHeaders h = new HttpHeaders();
        h.setContentDisposition(ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build());
        h.setContentType(type);
        return new ResponseEntity<>(bytes, h, HttpStatus.OK);
    }

    private static Path resultsDir(Path suiteFile) {
        Path dir = suiteFile.toAbsolutePath().getParent();
        return (dir == null ? Paths.get(".") : dir).resolve("results");
    }

    private static String base(Path p) {
        String n = p.getFileName().toString();
        for (String ext : List.of(".suite.json", ".json", ".xlsx")) {
            if (n.toLowerCase(Locale.ROOT).endsWith(ext)) return n.substring(0, n.length() - ext.length());
        }
        return n;
    }

    static String safeName(String s) {
        String n = s == null || s.isBlank() ? "suite" : s.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return n.length() > 80 ? n.substring(0, 80) : n;
    }

    static {
        Json.MAPPER.getFactory();
    }
}
