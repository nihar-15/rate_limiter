package com.nihar.nihar.web.controller;

import com.nihar.nihar.service.StateInspectionService;
import com.nihar.nihar.web.dto.ApiError;
import com.nihar.nihar.web.dto.StateResetResponse;
import com.nihar.nihar.web.dto.StateResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/state")
@Tag(name = "State administration",
        description = "Inspect and reset the Redis-backed rate limiting state")
public class StateAdminController {

    private final StateInspectionService stateInspectionService;

    public StateAdminController(StateInspectionService stateInspectionService) {
        this.stateInspectionService = stateInspectionService;
    }

    @GetMapping("/{userId}/{component}")
    @Operation(
            summary = "Inspect state for a user and component",
            description = """
                    Returns the state the algorithms maintain in Redis: the fixed window
                    counter and window start, the sliding window timestamps, and the token
                    bucket level with its last refill, together with the key's remaining TTL.

                    Disabled unless `ratelimiter.admin.state-inspection-enabled=true`, which
                    the `local` profile sets. It is deliberately off in every other profile so
                    per-user state is not exposed by accident.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current state"),
            @ApiResponse(responseCode = "403", description = "State inspection is disabled",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public StateResponse getState(
            @Parameter(description = "User identifier", example = "user-101")
            @PathVariable String userId,

            @Parameter(description = "Component identifier", example = "CODE_RUN")
            @PathVariable String component) {

        return stateInspectionService.getState(userId, component);
    }

    @DeleteMapping("/{userId}/{component}")
    @Operation(
            summary = "Reset state for a user and component",
            description = """
                    Deletes the Redis key holding the state, so the next request starts a fresh
                    window or a full bucket. Other users and components are untouched.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reset performed"),
            @ApiResponse(responseCode = "403", description = "State inspection is disabled",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public StateResetResponse resetState(
            @Parameter(description = "User identifier", example = "user-101")
            @PathVariable String userId,

            @Parameter(description = "Component identifier", example = "CODE_RUN")
            @PathVariable String component) {

        return stateInspectionService.resetState(userId, component);
    }

    @DeleteMapping
    @Operation(
            summary = "Reset all playground state",
            description = """
                    Deletes every `ratelimit:*` key. Intended for local experiments only and
                    refused unless `ratelimiter.admin.allow-reset-all=true`, which only the
                    `local` profile sets.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "All state reset"),
            @ApiResponse(responseCode = "403", description = "Reset-all is disabled",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public StateResetResponse resetAll() {
        return stateInspectionService.resetAll();
    }
}