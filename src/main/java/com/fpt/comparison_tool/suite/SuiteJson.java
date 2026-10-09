package com.fpt.comparison_tool.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.comparison_tool.model.Suite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Reads and writes suite files. A 1.x suite (testGroups) is converted on read. Writing goes through a temporary
 * file and keeps the previous content as name.bak.
 */
public final class SuiteJson {

    /** A suite as read, with the format it came from and conversion notes. */
    public record Loaded(Suite suite, String format, List<String> notes) {
    }

    private SuiteJson() {
    }

    public static Loaded parse(String text) {
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Not valid JSON: " + e.getOriginalMessage());
        }
        return parse(root);
    }

    public static Loaded parse(JsonNode root) {
        if (root == null || !root.isObject()) throw new IllegalArgumentException("A suite file is a JSON object");
        if (root.has("testGroups")) {
            V1Converter.Result r = V1Converter.convert(root);
            return new Loaded(r.suite(), "v1", r.notes());
        }
        if (!root.has("items") && !root.has("environments") && !root.has("schema")) {
            throw new IllegalArgumentException("This JSON is not a suite (no items, environments or schema)");
        }
        try {
            Suite suite = Json.MAPPER.treeToValue(root, Suite.class);
            return new Loaded(SuiteTree.ensureIds(suite), "v2", List.of());
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read suite: " + e.getMessage());
        }
    }

    public static Loaded read(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static String write(Suite suite) {
        return Json.pretty(suite);
    }

    public static void save(Suite suite, Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".suite", ".tmp");
        Files.writeString(tmp, write(suite), StandardCharsets.UTF_8);
        if (Files.exists(file)) {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
