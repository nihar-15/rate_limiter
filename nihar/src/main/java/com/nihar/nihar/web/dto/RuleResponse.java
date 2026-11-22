package com.nihar.nihar.web.dto;

import com.nihar.nihar.enums.RateLimitType;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * API view of a configured rule. Deliberately a separate type from the internal
 * {@code Rule} / {@code Path} entities so the storage model can evolve without
 * changing the contract.
 */
@Schema(name = "RuleResponse", description = "A configured rate limiting rule")
public record RuleResponse(

        @Schema(example = "CODE_RUN")
        String component,

        @Schema(example = "POST")
        String httpMethod,

        @Schema(example = "/api/demo/code-run")
        String apiPath,

        @Schema(example = "FREE")
        String plan,

        @Schema(example = "5")
        int limit,

        @Schema(example = "60")
        int windowInSeconds,

        @Schema(example = "FIXED_WINDOW")
        RateLimitType algorithm) {
}