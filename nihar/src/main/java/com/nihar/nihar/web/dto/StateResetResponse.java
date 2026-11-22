package com.nihar.nihar.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "StateResetResponse", description = "Result of a state reset operation")
public record StateResetResponse(

        @Schema(example = "user-101", description = "Null when every key was reset")
        String userId,

        @Schema(example = "CODE_RUN", description = "Null when every key was reset")
        String component,

        @Schema(description = "Number of Redis keys removed", example = "1")
        long keysRemoved,

        @Schema(example = "State reset")
        String message) {
}