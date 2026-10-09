package com.fpt.comparison_tool.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.comparison_tool.model.AuthProfile;
import com.fpt.comparison_tool.model.KeyValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Produces the Authorization header for an auth profile. OAuth2 tokens are cached until shortly before the expiry
 * the provider reports; a rotated refresh token is kept for the life of the process. A token failure is remembered
 * for the rest of the run so the identity provider is not called again for every request.
 */
public final class AuthService {

    private static final long DEFAULT_TTL_SECONDS = 3600L;
    private static final long EXPIRY_BUFFER_SECONDS = 60L;
    private static final long MIN_TTL_SECONDS = 30L;
    private static final int ERROR_BODY_LIMIT = 800;

    private final HttpExecutor http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();
    private final Map<String, String> rotatedRefreshTokens = new ConcurrentHashMap<>();
    private final Map<String, String> failures = new ConcurrentHashMap<>();
    private final Object fetchLock = new Object();

    private record CachedToken(String token, long expiresAtMillis) {
        boolean valid() {
            return System.currentTimeMillis() < expiresAtMillis;
        }
    }

    public AuthService(HttpExecutor http) {
        this.http = http;
    }

    /** Forget remembered token failures; called when a run starts. */
    public void resetFailures() {
        failures.clear();
    }

    public void clearCache() {
        tokens.clear();
        rotatedRefreshTokens.clear();
        failures.clear();
    }

    /** The Authorization header value for the profile, or null when the profile sends none. */
    public String authorization(AuthProfile profile, Function<String, String> vars) {
        if (profile == null) return null;
        return switch (profile.type()) {
            case AuthProfile.BASIC -> {
                String user = required(profile, "username", value(profile.username(), vars));
                String pass = required(profile, "password", value(profile.password(), vars));
                yield "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
            }
            case AuthProfile.BEARER -> "Bearer " + required(profile, "token", value(profile.token(), vars));
            case AuthProfile.OAUTH2 -> "Bearer " + accessToken(profile, vars);
            default -> null;
        };
    }

    private String accessToken(AuthProfile profile, Function<String, String> vars) {
        String grant = profile.resolvedGrant();
        String key = cacheKey(profile, grant, vars);
        String failed = failures.get(key);
        if (failed != null) throw new AuthTokenException(failed);
        CachedToken cached = tokens.get(key);
        if (cached != null && cached.valid()) return cached.token();
        synchronized (fetchLock) {
            cached = tokens.get(key);
            if (cached != null && cached.valid()) return cached.token();
            try {
                CachedToken fresh = fetch(profile, grant, vars);
                tokens.put(key, fresh);
                return fresh.token();
            } catch (AuthTokenException e) {
                failures.put(key, e.getMessage());
                throw e;
            }
        }
    }

    private CachedToken fetch(AuthProfile profile, String grant, Function<String, String> vars) {
        String tokenUrl = required(profile, "tokenUrl", value(profile.tokenUrl(), vars));
        List<KeyValue> form = new ArrayList<>();
        form.add(KeyValue.of("grant_type", grant));
        form.add(KeyValue.of("client_id", required(profile, "clientId", value(profile.clientId(), vars))));
        addIfPresent(form, "scope", value(profile.scope(), vars));
        switch (grant) {
            case "client_credentials" ->
                    form.add(KeyValue.of("client_secret", required(profile, "clientSecret", value(profile.clientSecret(), vars))));
            case "password" -> {
                form.add(KeyValue.of("username", required(profile, "username", value(profile.username(), vars))));
                form.add(KeyValue.of("password", required(profile, "password", value(profile.password(), vars))));
                addIfPresent(form, "client_secret", value(profile.clientSecret(), vars));
            }
            case "refresh_token" -> {
                String rotated = rotatedRefreshTokens.get(profile.name());
                String refresh = rotated != null ? rotated : value(profile.refreshToken(), vars);
                form.add(KeyValue.of("refresh_token", required(profile, "refreshToken", refresh)));
                addIfPresent(form, "client_secret", value(profile.clientSecret(), vars));
            }
            default -> throw new AuthTokenException(prefix(profile, grant)
                    + "unsupported grantType. Use client_credentials, password or refresh_token.");
        }
        for (KeyValue p : profile.extraParams()) {
            if (!p.active()) continue;
            String v = value(p.value(), vars);
            form.removeIf(f -> f.key().equals(p.key().trim()));
            form.add(KeyValue.of(p.key().trim(), v == null ? "" : v));
        }

        HttpExecutor.Reply reply;
        try {
            reply = http.send(new HttpExecutor.Call("POST", tokenUrl,
                    List.of(KeyValue.of("Content-Type", "application/x-www-form-urlencoded"), KeyValue.of("Accept", "application/json")),
                    Urls.form(form), 30_000), null);
        } catch (Exception e) {
            throw new AuthTokenException(prefix(profile, grant) + "token request to " + tokenUrl + " failed: "
                    + HttpExecutor.describe(e), e);
        }
        String raw = reply.body() == null ? "" : reply.body();
        if (reply.status() < 200 || reply.status() >= 300) {
            throw new AuthTokenException(prefix(profile, grant) + "token endpoint returned HTTP " + reply.status()
                    + " - " + truncate(raw));
        }
        JsonNode json;
        try {
            json = mapper.readTree(raw);
        } catch (Exception e) {
            throw new AuthTokenException(prefix(profile, grant) + "token response is not JSON - " + truncate(raw), e);
        }
        String token = json.path("access_token").asText(null);
        if (token == null || token.isBlank()) {
            throw new AuthTokenException(prefix(profile, grant) + "response contained no access_token - " + truncate(raw));
        }
        String rotated = json.path("refresh_token").asText(null);
        if (rotated != null && !rotated.isBlank()) rotatedRefreshTokens.put(profile.name(), rotated);
        long ttl = json.path("expires_in").asLong(DEFAULT_TTL_SECONDS);
        long effective = Math.max(MIN_TTL_SECONDS, ttl - EXPIRY_BUFFER_SECONDS);
        return new CachedToken(token, System.currentTimeMillis() + effective * 1000L);
    }

    private static String value(String raw, Function<String, String> vars) {
        String v = SecretResolver.resolve(raw);
        if (v == null) return null;
        Set<String> missing = new LinkedHashSet<>();
        v = Templates.resolve(v, vars, missing);
        return v;
    }

    private static void addIfPresent(List<KeyValue> form, String key, String v) {
        if (v != null && !v.isBlank()) form.add(KeyValue.of(key, v));
    }

    private static String required(AuthProfile profile, String field, String v) {
        if (v == null || v.isBlank()) {
            throw new AuthTokenException("Auth profile '" + profile.name() + "': " + field + " is required but empty.");
        }
        String env = SecretResolver.firstUnresolvedName(v);
        if (env != null) {
            throw new AuthTokenException("Auth profile '" + profile.name() + "': " + field
                    + " references environment variable " + env + ", which is not set on this machine.");
        }
        if (Templates.hasPlaceholders(v)) {
            throw new AuthTokenException("Auth profile '" + profile.name() + "': " + field + " uses "
                    + Templates.names(v) + ", which no step or environment has set.");
        }
        return v;
    }

    private static String prefix(AuthProfile profile, String grant) {
        return "Auth profile '" + profile.name() + "' (grant " + grant + "): ";
    }

    private static String cacheKey(AuthProfile profile, String grant, Function<String, String> vars) {
        return profile.name() + "|" + grant + "|" + value(profile.clientId(), vars) + "|"
                + Integer.toHexString(String.valueOf(value(profile.username(), vars)).hashCode()) + "|"
                + Integer.toHexString(String.valueOf(value(profile.tokenUrl(), vars)).hashCode());
    }

    private static String truncate(String s) {
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= ERROR_BODY_LIMIT ? flat : flat.substring(0, ERROR_BODY_LIMIT) + "...";
    }
}
