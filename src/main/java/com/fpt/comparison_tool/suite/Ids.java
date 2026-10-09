package com.fpt.comparison_tool.suite;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** Short identifiers for folders and test cases: random for new items, hash-based for converted ones. */
public final class Ids {

    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String random(String prefix) {
        StringBuilder sb = new StringBuilder(prefix).append('-');
        for (int i = 0; i < 8; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    public static String stable(String prefix, String seed) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(prefix).append('-');
            for (int i = 0; i < 8; i++) sb.append(ALPHABET.charAt((h[i] & 0xff) % ALPHABET.length()));
            return sb.toString();
        } catch (Exception e) {
            return random(prefix);
        }
    }
}
