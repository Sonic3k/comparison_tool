package com.fpt.comparison_tool.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.comparison_tool.model.AuthProfile;
import com.fpt.comparison_tool.model.Body;
import com.fpt.comparison_tool.model.CompareOptions;
import com.fpt.comparison_tool.model.Defaults;
import com.fpt.comparison_tool.model.Environment;
import com.fpt.comparison_tool.model.Expect;
import com.fpt.comparison_tool.model.Extract;
import com.fpt.comparison_tool.model.Folder;
import com.fpt.comparison_tool.model.KeyValue;
import com.fpt.comparison_tool.model.Node;
import com.fpt.comparison_tool.model.Retry;
import com.fpt.comparison_tool.model.RunPolicy;
import com.fpt.comparison_tool.model.Settings;
import com.fpt.comparison_tool.model.Step;
import com.fpt.comparison_tool.model.StepOptions;
import com.fpt.comparison_tool.model.Suite;
import com.fpt.comparison_tool.model.TestCase;
import com.fpt.comparison_tool.suite.Json;
import com.fpt.comparison_tool.suite.SuiteTree;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Suite as a workbook. Sheets: Suite (settings), Environments, Auth Profiles, Variables, Folders and Steps - one row
 * per step with the folder path and test case in front, plus "folder" and "case" rows that fix the tree order.
 * Text longer than an Excel cell holds goes to the Long Text sheet and the cell keeps a @long:N reference.
 */
public final class ExcelSuite {

    /** A suite read from a workbook with notes about what could not be read. */
    public record Imported(Suite suite, List<String> notes) {
    }

    static final String SEP = " / ";
    private static final int CELL_LIMIT = 32_000;
    private static final List<String> STEP_COLUMNS = List.of("Folder Path", "Case Key", "Case Name", "Case Description",
            "Tags", "Depends On", "Case Disabled", "Stop On Failure", "Kind", "#", "Step Name", "Step Disabled", "Method",
            "URL", "Query", "Headers", "Body Type", "Body", "Auth", "Extract", "Expected Status", "Assertions",
            "Max Time ms", "Delay ms", "Timeout ms", "Retry", "Compare", "Ignore Fields", "Required", "Notes", "Case Id",
            "Folder Id");
    private static final List<String> FOLDER_COLUMNS = List.of("Path", "Description", "Disabled", "Test Cases",
            "Sub-folders", "Default Headers", "Default Auth", "Timeout ms", "Folder Id");

    private ExcelSuite() {
    }

    // ================================================================= write

    public static void write(Suite suite, OutputStream os) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Writer w = new Writer(wb);
            w.suiteSheet(suite);
            w.environments(suite);
            w.auth(suite);
            w.variables(suite);
            w.folders(suite);
            w.steps(suite);
            w.longTextSheet();
            w.guide();
            wb.write(os);
        }
    }

    private static final class Writer {
        final Workbook wb;
        final CellStyle header;
        final CellStyle wrap;
        final CellStyle kindStyle;
        final List<String> longTexts = new ArrayList<>();

        Writer(Workbook wb) {
            this.wb = wb;
            Font bold = wb.createFont();
            bold.setBold(true);
            bold.setColor(IndexedColors.WHITE.getIndex());
            header = wb.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.SEA_GREEN.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            wrap = wb.createCellStyle();
            wrap.setWrapText(true);
            wrap.setVerticalAlignment(VerticalAlignment.TOP);
            kindStyle = wb.createCellStyle();
            kindStyle.cloneStyleFrom(wrap);
            kindStyle.setFillForegroundColor(IndexedColors.LEMON_CHIFFON.getIndex());
            kindStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }

        Sheet table(String name, List<String> columns, int[] widths) {
            Sheet s = wb.createSheet(name);
            Row r = s.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                Cell c = r.createCell(i);
                c.setCellValue(columns.get(i));
                c.setCellStyle(header);
                s.setColumnWidth(i, Math.min(255, i < widths.length ? widths[i] : 18) * 256);
            }
            s.createFreezePane(0, 1);
            return s;
        }

        void put(Row r, int col, String value) {
            if (value == null || value.isEmpty()) return;
            Cell c = r.createCell(col);
            if (value.length() > CELL_LIMIT) {
                longTexts.add(value);
                value = "@long:" + longTexts.size();
            }
            c.setCellValue(value);
            c.setCellStyle(wrap);
        }

        void suiteSheet(Suite suite) {
            Sheet s = table("Suite", List.of("Field", "Value"), new int[]{32, 90});
            Settings st = suite.settingsOrDefault();
            RunPolicy rp = st.runOrDefault();
            CompareOptions co = st.compareOrDefault();
            Defaults d = suite.defaultsOrEmpty();
            String[][] rows = {
                    {"Name", suite.name()},
                    {"Description", suite.description()},
                    {"Version", suite.version()},
                    {"Default Environment", st.env()},
                    {"Baseline Environment", st.baselineEnv()},
                    {"Concurrency", st.concurrency() == null ? null : String.valueOf(st.concurrency())},
                    {"Test Cases", rp.cases()},
                    {"Sub-folders", rp.folders()},
                    {"Timeout ms", d.timeoutMs() == null ? null : String.valueOf(d.timeoutMs())},
                    {"Default Auth", d.auth()},
                    {"Default Headers", headers(d.headers())},
                    {"Compare Ignore Fields", String.join(",", co.ignoreFields())},
                    {"Compare Ignore Array Order", yes(co.ignoreArrayOrder())},
                    {"Compare Case Sensitive", co.caseSensitiveOn() ? "Y" : "N"},
                    {"Compare Numeric Tolerance", co.numericTolerance() == null ? null : String.valueOf(co.numericTolerance())},
                    {"Compare Error Responses", yes(co.compareErrorResponses())},
            };
            int i = 1;
            for (String[] row : rows) {
                Row r = s.createRow(i++);
                put(r, 0, row[0]);
                put(r, 1, row[1]);
            }
        }

        void environments(Suite suite) {
            Sheet s = table("Environments", List.of("Name", "Base URL", "Auth Profile", "Headers", "Variables"),
                    new int[]{22, 50, 22, 50, 60});
            int i = 1;
            for (Environment e : suite.environments()) {
                Row r = s.createRow(i++);
                put(r, 0, e.name());
                put(r, 1, e.baseUrl());
                put(r, 2, e.auth());
                put(r, 3, headers(e.headers()));
                put(r, 4, pairs(e.variables()));
            }
        }

        void auth(Suite suite) {
            Sheet s = table("Auth Profiles", List.of("Name", "Type", "Grant Type", "Token URL", "Client ID", "Client Secret",
                    "Username", "Password", "Scope", "Token", "Refresh Token", "Extra Params", "Description"),
                    new int[]{20, 12, 18, 40, 22, 22, 18, 18, 20, 30, 30, 30, 30});
            int i = 1;
            for (AuthProfile p : suite.authProfiles()) {
                Row r = s.createRow(i++);
                String[] v = {p.name(), p.type(), p.grantType(), p.tokenUrl(), p.clientId(), p.clientSecret(), p.username(),
                        p.password(), p.scope(), p.token(), p.refreshToken(), pairs(p.extraParams()), p.description()};
                for (int c = 0; c < v.length; c++) put(r, c, v[c]);
            }
        }

        void variables(Suite suite) {
            Sheet s = table("Variables", List.of("Name", "Value"), new int[]{30, 80});
            int i = 1;
            for (KeyValue v : suite.variables()) {
                Row r = s.createRow(i++);
                put(r, 0, (v.disabled() != null ? "# " : "") + v.key());
                put(r, 1, v.value());
            }
        }

        void folders(Suite suite) {
            Sheet s = table("Folders", FOLDER_COLUMNS, new int[]{60, 50, 10, 12, 12, 50, 20, 12, 14});
            int[] i = {1};
            walkFolders(suite.items(), "", (path, f) -> {
                Row r = s.createRow(i[0]++);
                Defaults d = f.defaultsOrEmpty();
                RunPolicy rp = f.runOrDefault();
                put(r, 0, path);
                put(r, 1, f.description());
                put(r, 2, yes(f.disabled()));
                put(r, 3, rp.cases());
                put(r, 4, rp.folders());
                put(r, 5, headers(d.headers()));
                put(r, 6, d.auth());
                put(r, 7, d.timeoutMs() == null ? null : String.valueOf(d.timeoutMs()));
                put(r, 8, f.id());
            });
        }

        void steps(Suite suite) {
            Sheet s = table("Steps", STEP_COLUMNS, new int[]{40, 20, 40, 30, 14, 16, 8, 8, 14, 5, 40, 8, 9, 60, 30, 40,
                    10, 70, 14, 40, 10, 70, 10, 9, 10, 10, 9, 20, 9, 30, 14, 14});
            int[] row = {1};
            for (int i = 0; i < suite.setup().size(); i++) stepRow(s, row, "", null, null, "suite-setup", i, suite.setup().get(i));
            writeItems(s, row, suite.items(), "");
            for (int i = 0; i < suite.teardown().size(); i++) stepRow(s, row, "", null, null, "suite-teardown", i, suite.teardown().get(i));
            s.setAutoFilter(new CellRangeAddress(0, Math.max(0, row[0] - 1), 0, STEP_COLUMNS.size() - 1));
        }

        void writeItems(Sheet s, int[] row, List<Node> items, String path) {
            for (Node n : items) {
                if (n instanceof Folder f) {
                    String p = path.isEmpty() ? f.name() : path + SEP + f.name();
                    Row r = s.createRow(row[0]++);
                    put(r, 0, p);
                    put(r, 8, "folder");
                    r.getCell(8).setCellStyle(kindStyle);
                    put(r, 31, f.id());
                    for (int i = 0; i < f.setup().size(); i++) stepRow(s, row, p, null, f, "folder-setup", i, f.setup().get(i));
                    writeItems(s, row, f.items(), p);
                    for (int i = 0; i < f.teardown().size(); i++) stepRow(s, row, p, null, f, "folder-teardown", i, f.teardown().get(i));
                } else if (n instanceof TestCase tc) {
                    boolean[] first = {true};
                    if (tc.preconditions().isEmpty() && tc.steps().isEmpty() && tc.cleanup().isEmpty()) {
                        caseRow(s, row, path, tc, first);
                    }
                    for (int i = 0; i < tc.preconditions().size(); i++) {
                        Row r = caseRow(s, row, path, tc, first);
                        stepCells(r, "precondition", i, tc.preconditions().get(i));
                    }
                    for (int i = 0; i < tc.steps().size(); i++) {
                        Row r = caseRow(s, row, path, tc, first);
                        stepCells(r, "step", i, tc.steps().get(i));
                    }
                    for (int i = 0; i < tc.cleanup().size(); i++) {
                        Row r = caseRow(s, row, path, tc, first);
                        stepCells(r, "cleanup", i, tc.cleanup().get(i));
                    }
                }
            }
        }

        Row caseRow(Sheet s, int[] row, String path, TestCase tc, boolean[] first) {
            Row r = s.createRow(row[0]++);
            put(r, 0, path);
            put(r, 1, tc.key());
            if (first[0]) {
                put(r, 2, tc.name());
                put(r, 3, tc.description());
                put(r, 4, String.join(", ", tc.tags()));
                put(r, 5, String.join(", ", tc.dependsOn()));
                put(r, 6, yes(tc.disabled()));
                put(r, 7, yes(tc.stopOnFailure()));
                first[0] = false;
            }
            put(r, 8, "case");
            put(r, 30, tc.id());
            return r;
        }

        void stepRow(Sheet s, int[] row, String path, TestCase tc, Folder f, String kind, int index, Step step) {
            Row r = s.createRow(row[0]++);
            put(r, 0, path);
            if (f != null) put(r, 31, f.id());
            stepCells(r, kind, index, step);
        }

        void stepCells(Row r, String kind, int index, Step st) {
            put(r, 8, kind);
            put(r, 9, String.valueOf(index + 1));
            put(r, 10, st.name());
            put(r, 11, yes(st.disabled()));
            put(r, 12, st.method());
            put(r, 13, st.url());
            put(r, 14, pairs(st.query()));
            put(r, 15, headers(st.headers()));
            Body b = st.body();
            if (b != null) {
                put(r, 16, b.type());
                put(r, 17, switch (b.type()) {
                    case Body.FORM -> pairs(b.form());
                    case Body.JSON -> b.json() != null ? Json.pretty(b.json()).stripTrailing() : b.raw();
                    default -> b.raw();
                });
            }
            put(r, 18, st.auth());
            put(r, 19, extracts(st.extract()));
            Expect ex = st.expectOrEmpty();
            put(r, 20, ex.status());
            put(r, 21, String.join("\n", ex.assertions()));
            put(r, 22, ex.maxTimeMs() == null ? null : String.valueOf(ex.maxTimeMs()));
            StepOptions o = st.optionsOrNone();
            put(r, 23, o.delayMs() == null ? null : String.valueOf(o.delayMs()));
            put(r, 24, o.timeoutMs() == null ? null : String.valueOf(o.timeoutMs()));
            put(r, 25, o.retry() == null ? null : o.retry().timesOrZero() + " x " + o.retry().intervalOrDefault());
            put(r, 26, o.compareOn() ? null : "N");
            put(r, 27, String.join(",", o.ignoreFields()));
            put(r, 28, o.requiredOn() ? "Y" : null);
            put(r, 29, st.notes());
        }

        void longTextSheet() {
            if (longTexts.isEmpty()) return;
            Sheet s = table("Long Text", List.of("Ref", "Part", "Text"), new int[]{12, 8, 120});
            int row = 1;
            for (int i = 0; i < longTexts.size(); i++) {
                String t = longTexts.get(i);
                int part = 1;
                for (int off = 0; off < t.length(); off += CELL_LIMIT) {
                    Row r = s.createRow(row++);
                    r.createCell(0).setCellValue("@long:" + (i + 1));
                    r.createCell(1).setCellValue(part++);
                    r.createCell(2).setCellValue(t.substring(off, Math.min(t.length(), off + CELL_LIMIT)));
                }
            }
        }

        void guide() {
            Sheet s = table("Guide", List.of("Column", "Meaning"), new int[]{22, 120});
            String[][] rows = {
                    {"Kind", "folder | case | suite-setup | suite-teardown | folder-setup | folder-teardown | precondition | step | cleanup"},
                    {"Folder Path", "Folder names joined with \" / \"; empty for the suite level"},
                    {"Case columns", "Case Name, Description, Tags, Depends On, Disabled, Stop On Failure are read from the first row of a case"},
                    {"Query / Headers", "One per line: name=value / Name: value. A line starting with # is disabled"},
                    {"Body", "Body Type json (JSON text), form (name=value per line) or text"},
                    {"Extract", "One per line: name = $.json.path, $status, $header.Name or $; add [folder] or [suite] to share it"},
                    {"Assertions", "One per line, e.g. $.id == 1, $.list[*].code any == \"A\", $.items[key=\"x\"].v > 2, header Content-Type contains json"},
                    {"Expected Status", "200, 2xx or a list such as 200,404. Without status and assertions the step needs a status below 400"},
                    {"Retry", "times x interval ms, e.g. 3 x 1000: repeat the step until it passes"},
                    {"Compare", "N keeps the step out of the baseline/target diff of a compare run"},
                    {"Required", "Y on a setup step blocks the folder's test cases when the step does not pass"},
                    {"Case Id / Folder Id", "Keep them when editing so results stay linked; leave empty for new rows"},
            };
            int i = 1;
            for (String[] r : rows) {
                Row row = s.createRow(i++);
                put(row, 0, r[0]);
                put(row, 1, r[1]);
            }
        }
    }

    // ================================================================= read

    public static Imported read(InputStream in) throws IOException {
        List<String> notes = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(in)) {
            Reader rd = new Reader(wb);
            if (wb.getSheet("Steps") == null) throw new IllegalArgumentException("This workbook has no Steps sheet");
            Map<String, String> st = rd.keyValues("Suite");
            List<Environment> envs = new ArrayList<>();
            for (Map<String, String> r : rd.rows("Environments")) {
                if (blank(r.get("name"))) continue;
                envs.add(new Environment(r.get("name"), r.get("base url"), r.get("auth profile"), parseHeaders(r.get("headers")),
                        parsePairs(r.get("variables"))));
            }
            List<AuthProfile> profiles = new ArrayList<>();
            for (Map<String, String> r : rd.rows("Auth Profiles")) {
                if (blank(r.get("name"))) continue;
                profiles.add(new AuthProfile(r.get("name"), r.get("type"), r.get("grant type"), r.get("token url"),
                        r.get("client id"), r.get("client secret"), r.get("username"), r.get("password"), r.get("scope"),
                        r.get("token"), r.get("refresh token"), parsePairs(r.get("extra params")), r.get("description")));
            }
            List<KeyValue> vars = new ArrayList<>();
            for (Map<String, String> r : rd.rows("Variables")) {
                String n = r.get("name");
                if (blank(n)) continue;
                boolean off = n.startsWith("#");
                vars.add(new KeyValue(off ? n.substring(1).trim() : n.trim(), r.getOrDefault("value", ""), off ? Boolean.TRUE : null));
            }

            Tree tree = new Tree();
            List<Step> suiteSetup = new ArrayList<>();
            List<Step> suiteTeardown = new ArrayList<>();
            int rowNo = 1;
            for (Map<String, String> r : rd.rows("Steps")) {
                rowNo++;
                String kind = lower(r.get("kind"));
                String path = r.getOrDefault("folder path", "");
                if (kind.isEmpty()) {
                    if (blank(r.get("url")) && blank(r.get("case key"))) continue;
                    kind = blank(r.get("case key")) ? "folder-setup" : "step";
                }
                switch (kind) {
                    case "folder" -> tree.folder(path, r.get("folder id"));
                    case "suite-setup" -> suiteSetup.add(step(r));
                    case "suite-teardown" -> suiteTeardown.add(step(r));
                    case "folder-setup" -> tree.folder(path, r.get("folder id")).setup.add(step(r));
                    case "folder-teardown" -> tree.folder(path, r.get("folder id")).teardown.add(step(r));
                    case "case", "precondition", "step", "cleanup" -> {
                        CaseB c = tree.testCase(path, r.get("case key"), r.get("case id"));
                        if (c.name == null && !blank(r.get("case name"))) c.name = r.get("case name");
                        if (c.description == null && !blank(r.get("case description"))) c.description = r.get("case description");
                        if (c.tags == null && !blank(r.get("tags"))) c.tags = list(r.get("tags"));
                        if (c.dependsOn == null && !blank(r.get("depends on"))) c.dependsOn = list(r.get("depends on"));
                        if (isYes(r.get("case disabled"))) c.disabled = true;
                        if (isYes(r.get("stop on failure"))) c.stopOnFailure = true;
                        if (kind.equals("precondition")) c.pre.add(step(r));
                        else if (kind.equals("step")) c.steps.add(step(r));
                        else if (kind.equals("cleanup")) c.cleanup.add(step(r));
                    }
                    default -> notes.add("Steps row " + rowNo + ": unknown kind '" + kind + "' ignored");
                }
            }

            for (Map<String, String> r : rd.rows("Folders")) {
                if (blank(r.get("path"))) continue;
                FolderB f = tree.folder(r.get("path"), r.get("folder id"));
                f.description = r.get("description");
                f.disabled = isYes(r.get("disabled"));
                f.cases = r.get("test cases");
                f.folders = r.get("sub-folders");
                f.headers = parseHeaders(r.get("default headers"));
                f.auth = r.get("default auth");
                f.timeout = intOrNull(r.get("timeout ms"));
            }
            Settings settings = new Settings(intOrNull(st.get("concurrency")), st.get("default environment"),
                    st.get("baseline environment"), new RunPolicy(st.get("test cases"), st.get("sub-folders")),
                    new CompareOptions(list(st.get("compare ignore fields")), isYes(st.get("compare ignore array order")),
                            st.containsKey("compare case sensitive") ? !"n".equals(lower(st.get("compare case sensitive"))) : null,
                            doubleOrNull(st.get("compare numeric tolerance")), isYes(st.get("compare error responses"))));
            Defaults defaults = new Defaults(parseHeaders(st.get("default headers")), st.get("default auth"),
                    intOrNull(st.get("timeout ms")));
            Suite suite = new Suite(null, st.getOrDefault("name", "Imported suite"), st.get("description"), st.get("version"),
                    settings, envs, profiles, vars, defaults, suiteSetup, suiteTeardown, tree.root.build());
            return new Imported(SuiteTree.ensureIds(suite), notes);
        } catch (IOException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot read the workbook: " + e.getMessage(), e);
        }
    }

    private static Step step(Map<String, String> r) {
        String bodyType = lower(r.get("body type"));
        String bodyText = r.get("body");
        Body body = null;
        if (!blank(bodyText)) {
            if (bodyType.equals(Body.FORM)) {
                body = new Body(Body.FORM, null, null, parsePairs(bodyText));
            } else if (bodyType.equals(Body.TEXT)) {
                body = new Body(Body.TEXT, null, bodyText, null);
            } else {
                JsonNode node = null;
                try {
                    node = Json.MAPPER.readTree(bodyText);
                } catch (Exception ignored) {
                    // keep text
                }
                body = node != null && (node.isObject() || node.isArray()) ? new Body(Body.JSON, node, null, null)
                        : new Body(Body.JSON, null, bodyText, null);
            }
        }
        List<Extract> extracts = new ArrayList<>();
        for (String line : lines(r.get("extract"))) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String name = line.substring(0, eq).trim();
            String rest = line.substring(eq + 1).trim();
            String scope = null;
            if (rest.endsWith("]") && rest.lastIndexOf('[') > 0) {
                String tag = rest.substring(rest.lastIndexOf('[') + 1, rest.length() - 1).trim().toLowerCase(Locale.ROOT);
                if (tag.equals("folder") || tag.equals("suite")) {
                    scope = tag;
                    rest = rest.substring(0, rest.lastIndexOf('[')).trim();
                }
            }
            extracts.add(new Extract(name, rest, scope));
        }
        Retry retry = null;
        String rt = r.get("retry");
        if (!blank(rt)) {
            String[] p = rt.toLowerCase(Locale.ROOT).split("x");
            retry = new Retry(intOrNull(p[0].trim()), p.length > 1 ? intOrNull(p[1].trim()) : null);
        }
        StepOptions options = new StepOptions(intOrNull(r.get("delay ms")), intOrNull(r.get("timeout ms")), retry,
                "n".equals(lower(r.get("compare"))) ? Boolean.FALSE : null, list(r.get("ignore fields")),
                isYes(r.get("required")));
        Expect expect = new Expect(r.get("expected status"), lines(r.get("assertions")), intOrNull(r.get("max time ms")));
        return new Step(r.getOrDefault("step name", ""), isYes(r.get("step disabled")) ? Boolean.TRUE : null,
                r.get("method"), r.getOrDefault("url", ""), parsePairs(r.get("query")), parseHeaders(r.get("headers")),
                body, r.get("auth"), extracts, expect, options, r.get("notes"));
    }

    private static final class Reader {
        final Workbook wb;
        final DataFormatter fmt = new DataFormatter();
        final Map<String, String> longText = new HashMap<>();

        Reader(Workbook wb) {
            this.wb = wb;
            Sheet s = wb.getSheet("Long Text");
            if (s != null) {
                Map<String, StringBuilder> parts = new LinkedHashMap<>();
                for (int i = 1; i <= s.getLastRowNum(); i++) {
                    Row r = s.getRow(i);
                    if (r == null) continue;
                    String ref = cell(r, 0);
                    if (ref.isEmpty()) continue;
                    parts.computeIfAbsent(ref, k -> new StringBuilder()).append(cell(r, 2));
                }
                parts.forEach((k, v) -> longText.put(k, v.toString()));
            }
        }

        String cell(Row r, int i) {
            Cell c = r.getCell(i);
            if (c == null) return "";
            String v = c.getCellType() == CellType.STRING ? c.getStringCellValue() : fmt.formatCellValue(c);
            return v == null ? "" : v;
        }

        String value(Row r, int i) {
            String v = cell(r, i);
            if (v.startsWith("@long:") && longText.containsKey(v.trim())) return longText.get(v.trim());
            return v;
        }

        List<Map<String, String>> rows(String sheet) {
            List<Map<String, String>> out = new ArrayList<>();
            Sheet s = wb.getSheet(sheet);
            if (s == null) return out;
            Row head = s.getRow(0);
            if (head == null) return out;
            List<String> names = new ArrayList<>();
            for (int i = 0; i < head.getLastCellNum(); i++) names.add(cell(head, i).trim().toLowerCase(Locale.ROOT));
            for (int i = 1; i <= s.getLastRowNum(); i++) {
                Row r = s.getRow(i);
                if (r == null) continue;
                Map<String, String> m = new HashMap<>();
                boolean any = false;
                for (int c = 0; c < names.size(); c++) {
                    String v = value(r, c);
                    if (!v.isEmpty()) {
                        m.put(names.get(c), v);
                        any = true;
                    }
                }
                if (any) out.add(m);
            }
            return out;
        }

        Map<String, String> keyValues(String sheet) {
            Map<String, String> out = new HashMap<>();
            for (Map<String, String> r : rows(sheet)) {
                String k = r.get("field");
                if (k != null) out.put(k.trim().toLowerCase(Locale.ROOT), r.get("value"));
            }
            return out;
        }
    }

    // ---------------------------------------------------------------- tree building

    private static final class FolderB {
        String id;
        String name;
        String description;
        boolean disabled;
        String cases;
        String folders;
        List<KeyValue> headers = List.of();
        String auth;
        Integer timeout;
        final List<Step> setup = new ArrayList<>();
        final List<Step> teardown = new ArrayList<>();
        final List<Object> items = new ArrayList<>();

        List<Node> build() {
            List<Node> out = new ArrayList<>();
            for (Object o : items) {
                if (o instanceof FolderB f) {
                    out.add(new Folder(f.id, f.name, f.description, f.disabled ? Boolean.TRUE : null,
                            new Defaults(f.headers, f.auth, f.timeout), new RunPolicy(f.cases, f.folders), f.setup,
                            f.teardown, f.build()));
                } else if (o instanceof CaseB c) {
                    out.add(new TestCase(c.id, c.key, c.name == null ? "" : c.name, c.description, c.tags,
                            c.disabled ? Boolean.TRUE : null, c.dependsOn, c.stopOnFailure ? Boolean.TRUE : null, c.pre,
                            c.steps, c.cleanup));
                }
            }
            return out;
        }
    }

    private static final class CaseB {
        String id;
        String key;
        String name;
        String description;
        List<String> tags;
        List<String> dependsOn;
        boolean disabled;
        boolean stopOnFailure;
        final List<Step> pre = new ArrayList<>();
        final List<Step> steps = new ArrayList<>();
        final List<Step> cleanup = new ArrayList<>();
    }

    private static final class Tree {
        final FolderB root = new FolderB();
        final Map<String, FolderB> byPath = new HashMap<>();
        final Map<String, CaseB> cases = new HashMap<>();
        final java.util.Set<FolderB> placed = new java.util.HashSet<>();

        FolderB folder(String path, String id) {
            String p = normalize(path);
            if (p.isEmpty()) return root;
            FolderB f = byPath.get(p);
            if (f != null) {
                if (f.id == null && !blank(id)) f.id = id;
                return f;
            }
            int cut = p.lastIndexOf(SEP);
            FolderB parent = cut < 0 ? root : folder(p.substring(0, cut), null);
            f = new FolderB();
            f.name = cut < 0 ? p : p.substring(cut + SEP.length());
            f.id = blank(id) ? null : id;
            byPath.put(p, f);
            parent.items.add(f);
            placed.add(f);
            return f;
        }

        CaseB testCase(String path, String key, String id) {
            FolderB parent = folder(path, null);
            String k = normalize(path) + "\u0000" + (blank(id) ? (key == null ? "" : key.trim()) : id.trim());
            CaseB c = cases.get(k);
            if (c == null) {
                c = new CaseB();
                c.id = blank(id) ? null : id.trim();
                c.key = key == null ? "" : key.trim();
                cases.put(k, c);
                parent.items.add(c);
            }
            return c;
        }
    }

    // ---------------------------------------------------------------- text helpers

    static String headers(List<KeyValue> list) {
        StringBuilder sb = new StringBuilder();
        for (KeyValue h : list) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(h.disabled() != null ? "# " : "").append(h.key()).append(": ").append(h.value());
        }
        return sb.toString();
    }

    static String pairs(List<KeyValue> list) {
        StringBuilder sb = new StringBuilder();
        for (KeyValue h : list) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(h.disabled() != null ? "# " : "").append(h.key()).append('=').append(h.value());
        }
        return sb.toString();
    }

    static String extracts(List<Extract> list) {
        StringBuilder sb = new StringBuilder();
        for (Extract e : list) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(e.name()).append(" = ").append(e.path());
            if (e.scope() != null) sb.append(" [").append(e.scope()).append(']');
        }
        return sb.toString();
    }

    static List<KeyValue> parseHeaders(String text) {
        List<KeyValue> out = new ArrayList<>();
        for (String line : lines(text)) {
            boolean off = line.startsWith("#");
            String l = off ? line.substring(1).trim() : line;
            int idx = l.indexOf(':');
            if (idx > 0) out.add(new KeyValue(l.substring(0, idx).trim(), l.substring(idx + 1).trim(), off ? Boolean.TRUE : null));
        }
        return out;
    }

    static List<KeyValue> parsePairs(String text) {
        List<KeyValue> out = new ArrayList<>();
        for (String line : lines(text)) {
            boolean off = line.startsWith("#");
            String l = off ? line.substring(1).trim() : line;
            int idx = l.indexOf('=');
            if (idx > 0) out.add(new KeyValue(l.substring(0, idx).trim(), l.substring(idx + 1), off ? Boolean.TRUE : null));
            else if (!l.isEmpty()) out.add(new KeyValue(l, "", off ? Boolean.TRUE : null));
        }
        return out;
    }

    static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String l : text.split("\\r?\\n")) if (!l.isBlank()) out.add(l.trim());
        return out;
    }

    private static List<String> list(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String s : text.split("[,\\n]")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    private static String normalize(String path) {
        if (path == null) return "";
        String[] parts = path.split("\\s*/\\s*");
        List<String> clean = new ArrayList<>();
        for (String p : parts) if (!p.isBlank()) clean.add(p.trim());
        return String.join(SEP, clean);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isYes(String s) {
        String v = lower(s);
        return v.equals("y") || v.equals("yes") || v.equals("true") || v.equals("x") || v.equals("1");
    }

    private static String yes(Boolean b) {
        return Boolean.TRUE.equals(b) ? "Y" : null;
    }

    private static Integer intOrNull(String s) {
        if (blank(s)) return null;
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double doubleOrNull(String s) {
        if (blank(s)) return null;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private interface FolderVisitor {
        void visit(String path, Folder f);
    }

    private static void walkFolders(List<Node> items, String path, FolderVisitor v) {
        for (Node n : items) {
            if (n instanceof Folder f) {
                String p = path.isEmpty() ? f.name() : path + SEP + f.name();
                v.visit(p, f);
                walkFolders(f.items(), p, v);
            }
        }
    }
}
