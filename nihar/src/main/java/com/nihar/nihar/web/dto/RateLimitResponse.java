package com.nihar.nihar.web.dto;

import com.nihar.nihar.enums.RateLimitType;
import com.nihar.nihar.model.RateLimitDecision;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Result of a rate limited demo call. Returned with HTTP 200 when the request
 * was allowed and HTTP 429 when the limit was exceeded.
 */
@Schema(
        name = "RateLimitResponse",
        description = "Outcome of a rate limited request",
        example = """
                {
                  "userId": "user-101",
                  "component": "CODE_RUN",
                  "algorithm": "FIXED_WINDOW",
                  "allowed": true,
                  "limit": 5,
                  "windowInSeconds": 60,
                  "message": "Request allowed",
                  "plan": "FREE",
                  "remaining": 4,
                  "retryAfterSeconds": 0
                }
                """)
public record RateLimitResponse(

        @Schema(example = "user-101")
        String userId,

        @Schema(example = "CODE_RUN")
        String component,

        @Schema(example = "FIXED_WINDOW")
        RateLimitType algorithm,

        @Schema(example = "true")
        boolean allowed,

        @Schema(example = "5")
        int limit,

        @Schema(example = "60")
        int windowInSeconds,

        @Schema(example = "Request allowed")
        String message,

        @Schema(description = "Subscription plan the rule was resolved for", example = "FREE")
        String plan,

        @Schema(description = "Requests still available in the current window", example = "4")
        long remaining,

        @Schema(description = "Seconds to wait before retrying; 0 when allowed", example = "0")
        long retryAfterSeconds) {

    public static RateLimitResponse from(RateLimitDecision decision) {
        return new RateLimitResponse(
                decision.userId(),
                decision.component(),
                decision.algorithm(),
                decision.allowed(),
                decision.limit(),
                decision.windowInSeconds(),
                decision.message(),
                decision.plan(),
                decision.remaining(),
                decision.retryAfterSeconds());
    }
}