package com.nihar.nihar.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** Uniform error payload returned by every endpoint. */
@Schema(
        name = "ApiError",
        description = "Error payload",
        example = """
                {
                  "timestamp": "2026-11-19T10:15:30Z",
                  "status": 400,
                  "error": "VALIDATION_FAILED",
                  "message": "Request validation failed",
                  "details": ["limit must be at least 1"]
                }
                """)
public record ApiError(

        @Schema(example = "2026-11-19T10:15:30Z")
        Instant timestamp,

        @Schema(example = "400")
        int status,

        @Schema(example = "VALIDATION_FAILED")
        String error,

        @Schema(example = "Request validation failed")
        String message,

        @Schema(description = "Field level problems, when applicable")
        List<String> details) {

    public static ApiError of(int status, String error, String message) {
        return new ApiError(Instant.now(), status, error, message, List.of());
    }

    public static ApiError of(int status, String error, String message, List<String> details) {
        return new ApiError(Instant.now(), status, error, message, details);
    }
}