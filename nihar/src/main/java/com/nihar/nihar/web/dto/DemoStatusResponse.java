package com.nihar.nihar.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Response of GET /api/demo/status: the rate limiting outcome of the status
 * call itself, plus a snapshot of the caller's quota for every configured
 * component on their plan.
 */
@Schema(
        name = "DemoStatusResponse",
        description = "Rate limit outcome of the status call plus the caller's current quotas")
public record DemoStatusResponse(

        @Schema(description = "Outcome of rate limiting this status call")
        RateLimitResponse rateLimit,

        @Schema(description = "Quota snapshot per component, taken without consuming quota")
        List<QuotaSnapshot> quotas) {
}