package com.nihar.nihar.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Redis-backed rate limiting state for one user and component.
 *
 * <p>Only the fields the algorithms actually maintain are exposed; no raw Redis
 * payload, connection detail or other user's data is ever returned.</p>
 */
@Schema(name = "StateResponse", description = "Rate limiting state held in Redis")
public record StateResponse(

        @Schema(example = "user-101")
        String userId,

        @Schema(example = "CODE_RUN")
        String component,

        @Schema(description = "Whether Redis currently holds a state key", example = "true")
        boolean exists,

        @Schema(description = "Redis key holding this state", example = "ratelimit:user-101:CODE_RUN")
        String redisKey,

        @Schema(description = "Remaining key TTL in seconds; -1 no expiry, -2 no key", example = "57")
        long ttlSeconds,

        @Schema(description = "Fixed Window: requests counted in the current window", example = "3")
        int requestCount,

        @Schema(description = "Fixed Window: epoch millis the current window started", example = "1763500000000")
        long windowStartTimestamp,

        @Schema(description = "Sliding Window: epoch millis of the retained requests")
        List<Long> requestTimestamps,

        @Schema(description = "Token Bucket: tokens currently in the bucket", example = "2.75")
        double availableTokens,

        @Schema(description = "Token Bucket: epoch millis of the last refill", example = "1763500000000")
        long lastRefillTimestamp) {
}