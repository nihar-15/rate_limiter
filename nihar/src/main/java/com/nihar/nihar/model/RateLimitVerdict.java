package com.nihar.nihar.model;

/**
 * Outcome of a single rate limit evaluation, as produced by an algorithm.
 *
 * <p>This is deliberately algorithm agnostic: {@code remaining} is the number
 * of further requests the caller may make right now, and
 * {@code retryAfterMillis} is how long it must wait before the next request can
 * succeed (zero when the request was allowed).</p>
 */
public record RateLimitVerdict(
        boolean allowed,
        long remaining,
        long retryAfterMillis,
        DenialReason reason) {

    public enum DenialReason {
        NONE,
        LIMIT_EXCEEDED,
        USER_BLOCKED,
        USER_RESTRICTED
    }

    public static RateLimitVerdict allowed(long remaining) {
        return new RateLimitVerdict(true, remaining, 0L, DenialReason.NONE);
    }

    public static RateLimitVerdict denied(long remaining, long retryAfterMillis) {
        return new RateLimitVerdict(
                false, remaining, retryAfterMillis, DenialReason.LIMIT_EXCEEDED);
    }

    public static RateLimitVerdict blocked() {
        return new RateLimitVerdict(false, 0L, 0L, DenialReason.USER_BLOCKED);
    }

    public static RateLimitVerdict restricted() {
        return new RateLimitVerdict(false, 0L, 0L, DenialReason.USER_RESTRICTED);
    }

    /** Retry-After is expressed in whole seconds, rounded up. */
    public long retryAfterSeconds() {
        if (retryAfterMillis <= 0) {
            return 0L;
        }
        return (retryAfterMillis + 999L) / 1000L;
    }
}