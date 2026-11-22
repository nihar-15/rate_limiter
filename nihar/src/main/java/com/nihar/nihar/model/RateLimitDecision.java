package com.nihar.nihar.model;

import com.nihar.nihar.enums.RateLimitType;

/**
 * Fully resolved result of a demo request: the verdict plus the identity and
 * rule context that produced it. This is the internal model the web layer maps
 * into {@code RateLimitResponse}.
 */
public record RateLimitDecision(
        String userId,
        String plan,
        String component,
        RateLimitType algorithm,
        boolean allowed,
        int limit,
        int windowInSeconds,
        long remaining,
        long retryAfterSeconds,
        String message) {

    public static final String ALLOWED_MESSAGE = "Request allowed";
    public static final String LIMIT_EXCEEDED_MESSAGE = "Rate limit exceeded";
    public static final String BLOCKED_MESSAGE = "User is blocked for this component";
    public static final String RESTRICTED_MESSAGE = "User is temporarily restricted for this component";

    public static String messageFor(RateLimitVerdict verdict) {
        return switch (verdict.reason()) {
            case NONE -> ALLOWED_MESSAGE;
            case LIMIT_EXCEEDED -> LIMIT_EXCEEDED_MESSAGE;
            case USER_BLOCKED -> BLOCKED_MESSAGE;
            case USER_RESTRICTED -> RESTRICTED_MESSAGE;
        };
    }
}