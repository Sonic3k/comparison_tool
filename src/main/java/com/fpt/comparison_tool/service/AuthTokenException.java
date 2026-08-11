package com.fpt.comparison_tool.service;

/**
 * Thrown when an access token cannot be obtained for an auth profile.
 *
 * This is deliberately distinct from an ordinary request failure. A token
 * problem is a configuration problem: it will affect every remaining request
 * identically, so ExecutionService rethrows it and aborts the run instead of
 * recording thousands of identical errors and hammering the identity provider
 * with one token request per test case.
 */
public class AuthTokenException extends RuntimeException {

    public AuthTokenException(String message) {
        super(message);
    }

    public AuthTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
