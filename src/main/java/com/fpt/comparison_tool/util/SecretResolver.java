package com.fpt.comparison_tool.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves ${VAR} / ${VAR:default} placeholders against OS environment
 * variables, falling back to JVM system properties.
 *
 * Why this exists: a suite file is meant to be committed to git and run by CI.
 * Credentials must not live inside it. With this resolver a profile can declare
 *
 *     "clientSecret": "${OOG_CLIENT_SECRET}"
 *
 * and the CI runner supplies the real value as a masked pipeline variable.
 *
 * Deliberate choice: a placeholder with no matching variable and no default is
 * left untouched rather than blanked. Callers use {@link #hasUnresolved} to
 * detect it and fail with a message naming the variable, instead of silently
 * sending an empty secret and getting an opaque 401 back from the IdP.
 *
 * Note this is a different mechanism from the {{var}} syntax used inside test
 * requests: {{var}} is resolved from suite/environment variables at execution
 * time, ${VAR} is resolved from the machine running the tool.
 */
public final class SecretResolver {

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.]*)(?::([^}]*))?\\}");

    private SecretResolver() {}

    /** Returns the value with every resolvable ${VAR} replaced. Null-safe. */
    public static String resolve(String value) {
        if (value == null || value.isEmpty() || !value.contains("${")) return value;

        Matcher m = PLACEHOLDER.matcher(value);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name     = m.group(1);
            String fallback = m.group(2);

            String resolved = System.getenv(name);
            if (resolved == null) resolved = System.getProperty(name);
            if (resolved == null) resolved = fallback;
            if (resolved == null) resolved = m.group(0);   // leave placeholder visible

            m.appendReplacement(out, Matcher.quoteReplacement(resolved));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** True when the value still carries an unresolved ${VAR} placeholder. */
    public static boolean hasUnresolved(String value) {
        return value != null && PLACEHOLDER.matcher(value).find();
    }

    /** Name of the first unresolved placeholder, or null when there is none. */
    public static String firstUnresolvedName(String value) {
        if (value == null) return null;
        Matcher m = PLACEHOLDER.matcher(value);
        return m.find() ? m.group(1) : null;
    }
}
