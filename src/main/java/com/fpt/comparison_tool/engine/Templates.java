package com.fpt.comparison_tool.engine;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces {{name}} placeholders. Names are looked up through the caller's resolver; built-ins are $guid,
 * $randomUUID, $timestamp (epoch seconds), $timestampMs, $isoTimestamp and $randomInt (0-999).
 * Placeholders that cannot be resolved are left in the text and reported.
 */
public final class Templates {

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([^{}\\s]+?)\\s*}}");

    private Templates() {
    }

    public static String resolve(String text, Function<String, String> lookup, Set<String> unresolved) {
        if (text == null || text.isEmpty() || !text.contains("{{")) return text;
        Matcher m = VAR.matcher(text);
        StringBuilder out = new StringBuilder(text.length() + 32);
        while (m.find()) {
            String name = m.group(1);
            String value = name.startsWith("$") ? builtIn(name) : null;
            if (value == null) value = lookup.apply(name);
            if (value == null) {
                if (unresolved != null) unresolved.add(name);
                value = m.group(0);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    public static boolean hasPlaceholders(String text) {
        return text != null && VAR.matcher(text).find();
    }

    public static Set<String> names(String text) {
        Set<String> names = new java.util.LinkedHashSet<>();
        if (text == null) return names;
        Matcher m = VAR.matcher(text);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    private static String builtIn(String name) {
        return switch (name) {
            case "$guid", "$randomUUID" -> UUID.randomUUID().toString();
            case "$timestamp" -> String.valueOf(Instant.now().getEpochSecond());
            case "$timestampMs" -> String.valueOf(System.currentTimeMillis());
            case "$isoTimestamp" -> Instant.now().toString();
            case "$randomInt" -> String.valueOf(ThreadLocalRandom.current().nextInt(1000));
            default -> null;
        };
    }
}
