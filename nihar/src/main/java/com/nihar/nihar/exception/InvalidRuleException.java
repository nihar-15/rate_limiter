package com.nihar.nihar.exception;

/** The rule definition is structurally valid but semantically unusable. */
public class InvalidRuleException extends RuntimeException {

    public InvalidRuleException(String message) {
        super(message);
    }
}