package com.fpt.comparison_tool.result;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.comparison_tool.engine.RunListener;
import com.fpt.comparison_tool.suite.Json;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Run files are JSON Lines: one header line, one line per hook and per test case in the order they finished, and a
 * summary line. Lines are written as results arrive, so a run that is interrupted still leaves what it did.
 */
public final class RunFile {

    private RunFile() {
    }

    /** Appends run events to a file as they happen. */
    public static final class Writer implements RunListener, Closeable {
        private final java.io.Writer out;
        private IOException failure;

        public Writer(Path file) throws IOException {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        }

        @Override
        public void started(RunHeader header) {
            line("header", header);
        }

        @Override
        public void hook(HookResult result) {
            line("hook", result);
        }

        @Override
        public void caseFinished(CaseResult result) {
            line("case", result);
        }

        @Override
        public void finished(RunSummary summary) {
            line("summary", summary);
        }

        private synchronized void line(String kind, Object value) {
            if (failure != null) return;
            try {
                out.write(toLine(kind, value));
                out.write('\n');
                out.flush();
            } catch (IOException e) {
                failure = e;
            }
        }

        public IOException failure() {
            return failure;
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    public static String toLine(String kind, Object value) {
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("kind", kind);
        node.setAll((ObjectNode) Json.MAPPER.valueToTree(value));
        return Json.compact(node);
    }

    public static void write(RunData data, OutputStream os) throws IOException {
        Writer w = null;
        try (java.io.Writer out = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {
            if (data.header() != null) out.write(toLine("header", data.header()) + "\n");
            for (HookResult h : data.hooks()) out.write(toLine("hook", h) + "\n");
            for (CaseResult c : data.cases()) out.write(toLine("case", c) + "\n");
            if (data.summary() != null) out.write(toLine("summary", data.summary()) + "\n");
        }
    }

    public static RunData read(InputStream in) throws IOException {
        RunHeader header = null;
        RunSummary summary = null;
        List<HookResult> hooks = new ArrayList<>();
        List<CaseResult> cases = new ArrayList<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        int n = 0;
        while ((line = r.readLine()) != null) {
            n++;
            if (line.isBlank()) continue;
            JsonNode node;
            try {
                node = Json.MAPPER.readTree(line);
            } catch (IOException e) {
                throw new IOException("Line " + n + " of the run file is not valid JSON", e);
            }
            String kind = node.path("kind").asText("");
            ((ObjectNode) node).remove("kind");
            switch (kind) {
                case "header" -> header = Json.MAPPER.treeToValue(node, RunHeader.class);
                case "hook" -> hooks.add(Json.MAPPER.treeToValue(node, HookResult.class));
                case "case" -> cases.add(Json.MAPPER.treeToValue(node, CaseResult.class));
                case "summary" -> summary = Json.MAPPER.treeToValue(node, RunSummary.class);
                default -> {
                    // unknown line kinds are ignored so newer files stay readable
                }
            }
        }
        if (header == null) throw new IOException("Not a run file: the header line is missing");
        return new RunData(header, hooks, cases, summary);
    }

    public static RunData read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in);
        }
    }
}
