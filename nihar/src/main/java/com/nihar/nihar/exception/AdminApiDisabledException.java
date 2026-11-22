package com.nihar.nihar.exception;

/**
 * Raised when a playground-only administrative operation is invoked while it is
 * switched off, which is the default outside the local development profile.
 */
public class AdminApiDisabledException extends RuntimeException {

    public AdminApiDisabledException(String message) {
        super(message);
    }
}