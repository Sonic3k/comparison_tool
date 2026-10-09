package com.fpt.comparison_tool.engine;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves ${VAR} and ${VAR:default} against the machine environment, then JVM system properties. An unresolvable
 * placeholder is left in place so callers can name the missing variable instead of sending an empty secret.
 */
public final class SecretResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.]*)(?::([^}]*))?}");

    private SecretResolver() {
    }

    public static String resolve(String value) {
        if (value == null || value.isEmpty() || !value.contains("${")) return value;
        Matcher m = PLACEHOLDER.matcher(value);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String resolved = System.getenv(m.group(1));
            if (resolved == null) resolved = System.getProperty(m.group(1));
            if (resolved == null) resolved = m.group(2);
            if (resolved == null) resolved = m.group(0);
            m.appendReplacement(out, Matcher.quoteReplacement(resolved));
        }
        m.appendTail(out);
        return out.toString();
    }

    public static String firstUnresolvedName(String value) {
        if (value == null) return null;
        Matcher m = PLACEHOLDER.matcher(value);
        return m.find() ? m.group(1) : null;
    }
}
