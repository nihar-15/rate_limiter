package com.nihar.nihar.exception;


/** No rate limit rule is configured for the requested component  plan  path. **/
public class RuleNotFoundException extends RuntimeException {

    public RuleNotFoundException(String message) {
        super(message);
    }
}