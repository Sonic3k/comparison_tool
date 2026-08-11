package com.fpt.comparison_tool.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.comparison_tool.model.AuthProfile;
import com.fpt.comparison_tool.model.AuthType;
import com.fpt.comparison_tool.model.Param;
import com.fpt.comparison_tool.util.SecretResolver;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies an AuthProfile to outgoing request headers, fetching an OAuth2 token
 * when the profile needs one.
 *
 * Three properties matter for long unattended runs:
 *
 *   1. The grant is chosen per profile, not hard-coded. client_credentials
 *      yields an application token with no user identity; password and
 *      refresh_token yield user tokens, which is what APIs that read claims
 *      such as preferred_username require.
 *
 *   2. Tokens expire. The cache stores the expiry reported by the provider and
 *      fetches a new token when it lapses, so a run longer than the token
 *      lifetime does not start failing halfway through.
 *
 *   3. A failed token request aborts the run. The shared RestTemplate is
 *      configured never to throw on 4xx/5xx, so the failure has to be detected
 *      explicitly here; otherwise every subsequent request would be sent with
 *      no Authorization header and the real cause would be invisible.
 */
@Service
public class AuthService {

    private static final String ACCESS_TOKEN  = "access_token";
    private static final String EXPIRES_IN    = "expires_in";
    private static final String REFRESH_TOKEN = "refresh_token";

    /** Used when the provider does not report expires_in. */
    private static final long DEFAULT_TTL_SECONDS = 3600L;

    /** Renew this long before the reported expiry, to cover clock skew and in-flight requests. */
    private static final long EXPIRY_BUFFER_SECONDS = 60L;

    /** Never cache for less than this, so a tiny TTL cannot turn into a token call per request. */
    private static final long MIN_TTL_SECONDS = 30L;

    private static final int ERROR_BODY_LIMIT = 800;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    /**
     * Providers may rotate the refresh token on every use. The rotated value is
     * kept for the lifetime of the process so a long run keeps working; it is
     * not written back to the suite file.
     */
    private final Map<String, String> rotatedRefreshTokens = new ConcurrentHashMap<>();

    private final Object fetchLock = new Object();

    public AuthService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    private record CachedToken(String token, long expiresAtMillis) {
        boolean isValid() { return System.currentTimeMillis() < expiresAtMillis; }
    }

    /**
     * Applies authentication from the given profile to the provided headers.
     * Mutates the headers map directly.
     */
    public void applyAuth(AuthProfile profile, HttpHeaders headers) {
        if (profile == null || profile.getType() == null || profile.getType() == AuthType.NONE) return;

        switch (profile.getType()) {
            case BASIC              -> applyBasic(profile, headers);
            case BEARER             -> applyBearer(profile, headers);
            case CLIENT_CREDENTIALS -> applyOAuth2(profile, headers);
            default                 -> { /* SAML is not supported - browser flow, cannot run headless */ }
        }
    }

    // --- Auth strategies ------------------------------------------------------

    private void applyBasic(AuthProfile profile, HttpHeaders headers) {
        String user = required(profile, "username", value(profile.getUsername()));
        String pass = required(profile, "password", value(profile.getPassword()));
        String encoded = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes());
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
    }

    private void applyBearer(AuthProfile profile, HttpHeaders headers) {
        String token = required(profile, "token", value(profile.getToken()));
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private void applyOAuth2(AuthProfile profile, HttpHeaders headers) {
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(profile));
    }

    // --- Token acquisition ----------------------------------------------------

    /**
     * Returns a valid access token for the profile, fetching or renewing it as
     * needed.
     *
     * @throws AuthTokenException when the token cannot be obtained
     */
    public String accessToken(AuthProfile profile) {
        String grant = profile.resolvedGrantType();
        String key   = cacheKey(profile, grant);

        CachedToken cached = tokenCache.get(key);
        if (cached != null && cached.isValid()) return cached.token();

        synchronized (fetchLock) {
            cached = tokenCache.get(key);
            if (cached != null && cached.isValid()) return cached.token();

            CachedToken fresh = fetchToken(profile, grant);
            tokenCache.put(key, fresh);
            return fresh.token();
        }
    }

    private CachedToken fetchToken(AuthProfile profile, String grant) {
        String tokenUrl = required(profile, "tokenUrl", value(profile.getTokenUrl()));

        HttpHeaders tokenHeaders = new HttpHeaders();
        tokenHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = buildTokenBody(profile, grant);
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, tokenHeaders);

        ResponseEntity<String> response;
        try {
            response = restTemplate.postForEntity(tokenUrl, request, String.class);
        } catch (Exception e) {
            throw new AuthTokenException(prefix(profile, grant)
                    + "token request to " + tokenUrl + " failed: " + e, e);
        }

        String raw = response.getBody() != null ? response.getBody() : "";

        // The shared RestTemplate never throws on error status, so check it here.
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new AuthTokenException(prefix(profile, grant)
                    + "token endpoint returned HTTP " + response.getStatusCode().value()
                    + " - " + truncate(raw));
        }

        JsonNode json;
        try {
            json = objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new AuthTokenException(prefix(profile, grant)
                    + "token response is not JSON - " + truncate(raw), e);
        }

        String token = json.path(ACCESS_TOKEN).asText(null);
        if (token == null || token.isBlank()) {
            throw new AuthTokenException(prefix(profile, grant)
                    + "response contained no access_token - " + truncate(raw));
        }

        String rotated = json.path(REFRESH_TOKEN).asText(null);
        if (rotated != null && !rotated.isBlank()) {
            rotatedRefreshTokens.put(profileKey(profile), rotated);
        }

        long ttl = json.path(EXPIRES_IN).asLong(DEFAULT_TTL_SECONDS);
        long effective = Math.max(MIN_TTL_SECONDS, ttl - EXPIRY_BUFFER_SECONDS);
        return new CachedToken(token, System.currentTimeMillis() + effective * 1000L);
    }

    /**
     * Builds the token request body for the chosen grant. extraParams are added
     * last and overwrite anything above them, which is what makes the profile
     * usable against providers with their own required parameters.
     */
    private MultiValueMap<String, String> buildTokenBody(AuthProfile profile, String grant) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", grant);
        body.add("client_id", required(profile, "clientId", value(profile.getClientId())));
        addIfPresent(body, "scope", value(profile.getScope()));

        switch (grant) {
            case AuthProfile.GRANT_CLIENT_CREDENTIALS ->
                body.add("client_secret", required(profile, "clientSecret", value(profile.getClientSecret())));

            case AuthProfile.GRANT_PASSWORD -> {
                body.add("username", required(profile, "username", value(profile.getUsername())));
                body.add("password", required(profile, "password", value(profile.getPassword())));
                // Confidential clients still send the secret; public clients omit it.
                addIfPresent(body, "client_secret", value(profile.getClientSecret()));
            }

            case AuthProfile.GRANT_REFRESH_TOKEN -> {
                body.add("refresh_token", required(profile, "refreshToken", currentRefreshToken(profile)));
                addIfPresent(body, "client_secret", value(profile.getClientSecret()));
            }

            default -> throw new AuthTokenException(prefix(profile, grant)
                    + "unsupported grantType. Use client_credentials, password or refresh_token.");
        }

        if (profile.getExtraParams() != null) {
            for (Param p : profile.getExtraParams()) {
                if (p == null || p.getKey() == null || p.getKey().isBlank()) continue;
                body.set(p.getKey().trim(), value(p.getValue()) != null ? value(p.getValue()) : "");
            }
        }
        return body;
    }

    /** Rotated value from an earlier refresh if there is one, otherwise the configured value. */
    private String currentRefreshToken(AuthProfile profile) {
        String rotated = rotatedRefreshTokens.get(profileKey(profile));
        return rotated != null ? rotated : value(profile.getRefreshToken());
    }

    // --- Cache management -----------------------------------------------------

    /** Clears cached tokens - call when auth profiles are updated */
    public void clearCache() {
        tokenCache.clear();
        rotatedRefreshTokens.clear();
    }

    /** Clears cached token for a specific profile */
    public void clearCache(String profileName) {
        if (profileName == null) return;
        tokenCache.keySet().removeIf(k -> k.startsWith(profileName + "|"));
        rotatedRefreshTokens.remove(profileName);
    }

    // --- Helpers --------------------------------------------------------------

    private static String value(String raw) {
        return SecretResolver.resolve(raw);
    }

    private static void addIfPresent(MultiValueMap<String, String> body, String key, String v) {
        if (v != null && !v.isBlank()) body.add(key, v);
    }

    /**
     * Returns the value, or fails with a message naming the profile and field.
     * An unresolved ${VAR} is treated as missing and names the variable, since
     * a missing pipeline secret is the most likely cause in CI.
     */
    private static String required(AuthProfile profile, String field, String v) {
        if (v == null || v.isBlank()) {
            throw new AuthTokenException("Auth profile '" + safeName(profile) + "': " + field + " is required but empty.");
        }
        if (SecretResolver.hasUnresolved(v)) {
            throw new AuthTokenException("Auth profile '" + safeName(profile) + "': " + field
                    + " references environment variable " + SecretResolver.firstUnresolvedName(v)
                    + ", which is not set on this machine.");
        }
        return v;
    }

    private static String prefix(AuthProfile profile, String grant) {
        return "Auth profile '" + safeName(profile) + "' (grant " + grant + "): ";
    }

    private static String safeName(AuthProfile profile) {
        return profile == null || profile.getName() == null ? "unnamed" : profile.getName();
    }

    private static String profileKey(AuthProfile profile) {
        return safeName(profile);
    }

    /** Cache key covers grant and client so editing a profile cannot serve a stale token. */
    private static String cacheKey(AuthProfile profile, String grant) {
        return safeName(profile) + "|" + grant + "|" + (profile.getClientId() == null ? "" : profile.getClientId());
    }

    private static String truncate(String s) {
        if (s == null) return "";
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= ERROR_BODY_LIMIT ? flat : flat.substring(0, ERROR_BODY_LIMIT) + "...";
    }
}
