package com.nihar.nihar.web.dto;

import com.nihar.nihar.enums.RateLimitType;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Read-only view of how much quota a caller currently has for one component.
 * Computed without consuming any quota.
 */
@Schema(name = "QuotaSnapshot", description = "Current quota for one component")
public record QuotaSnapshot(

        @Schema(example = "CODE_RUN")
        String component,

        @Schema(example = "POST")
        String httpMethod,

        @Schema(example = "/api/demo/code-run")
        String apiPath,

        @Schema(example = "FIXED_WINDOW")
        RateLimitType algorithm,

        @Schema(example = "5")
        int limit,

        @Schema(example = "60")
        int windowInSeconds,

        @Schema(description = "Requests estimated to still be available", example = "3")
        long remaining,

        @Schema(description = "Whether Redis currently holds state for this caller", example = "true")
        boolean stateExists) {
}