package com.nihar.nihar.exception;

/**
 * The requested rule clashes with an already configured one, e.g. creating a
 * rule that already exists, or mapping an API path that another component
 * already owns.
 */
public class RuleConflictException extends RuntimeException {

    public RuleConflictException(String message) {
        super(message);
    }
}