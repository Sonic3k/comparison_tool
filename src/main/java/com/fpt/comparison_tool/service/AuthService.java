package com.fpt.comparison_tool.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.comparison_tool.model.AuthProfile;
import com.fpt.comparison_tool.model.AuthType;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Cache OAuth2 tokens: profileName → accessToken (no expiry handling for simplicity)
    private final Map<String, String> tokenCache = new ConcurrentHashMap<>();

    public AuthService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Applies authentication from the given profile to the provided headers.
     * Mutates the headers map directly.
     */
    public void applyAuth(AuthProfile profile, HttpHeaders headers) {
        if (profile == null || profile.getType() == AuthType.NONE) return;

        switch (profile.getType()) {
            case BASIC           -> applyBasic(profile, headers);
            case BEARER          -> applyBearer(profile, headers);
            case CLIENT_CREDENTIALS -> applyOAuth2(profile, headers);
            default              -> { /* SAML not handled */ }
        }
    }

    // ─── Auth strategies ──────────────────────────────────────────────────────

    private void applyBasic(AuthProfile profile, HttpHeaders headers) {
        String credentials = profile.getUsername() + ":" + profile.getPassword();
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
    }

    private void applyBearer(AuthProfile profile, HttpHeaders headers) {
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + profile.getToken());
    }

    private void applyOAuth2(AuthProfile profile, HttpHeaders headers) {
        String token = tokenCache.computeIfAbsent(profile.getName(), k -> fetchOAuth2Token(profile));
        if (token != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
    }

    private String fetchOAuth2Token(AuthProfile profile) {
        try {
            HttpHeaders tokenHeaders = new HttpHeaders();
            tokenHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "client_credentials");
            body.add("client_id", profile.getClientId());
            body.add("client_secret", profile.getClientSecret());
            if (profile.getScope() != null) body.add("scope", profile.getScope());

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, tokenHeaders);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    profile.getTokenUrl(), request, String.class);

            JsonNode json = objectMapper.readTree(response.getBody());
            return json.path("access_token").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Clears cached tokens — call when auth profiles are updated */
    public void clearCache() {
        tokenCache.clear();
    }

    /** Clears cached token for a specific profile */
    public void clearCache(String profileName) {
        tokenCache.remove(profileName);
    }
}