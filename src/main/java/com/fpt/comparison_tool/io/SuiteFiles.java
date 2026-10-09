package com.fpt.comparison_tool.io;

import com.fpt.comparison_tool.suite.SuiteJson;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Opens a suite from a .json file (current or 1.x format) or an .xlsx workbook. */
public final class SuiteFiles {

    private SuiteFiles() {
    }

    public static SuiteJson.Loaded read(Path file) throws IOException {
        return read(file.getFileName().toString(), Files.readAllBytes(file));
    }

    public static SuiteJson.Loaded read(String fileName, byte[] content) throws IOException {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        boolean zip = content.length > 3 && content[0] == 'P' && content[1] == 'K';
        if (name.endsWith(".xlsx") || zip) {
            try (InputStream in = new ByteArrayInputStream(content)) {
                ExcelSuite.Imported imp = ExcelSuite.read(in);
                return new SuiteJson.Loaded(imp.suite(), "excel", imp.notes());
            }
        }
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        return SuiteJson.parse(text);
    }
}
