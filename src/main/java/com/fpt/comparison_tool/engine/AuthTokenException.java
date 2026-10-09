package com.fpt.comparison_tool.engine;

/** A token could not be obtained; every request using the profile would fail the same way. */
public class AuthTokenException extends RuntimeException {

    public AuthTokenException(String message) {
        super(message);
    }

    public AuthTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
