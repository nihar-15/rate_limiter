package com.nihar.nihar.web.controller;

import com.nihar.nihar.service.RuleService;
import com.nihar.nihar.web.dto.ApiError;
import com.nihar.nihar.web.dto.RuleRequest;
import com.nihar.nihar.web.dto.RuleResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/rules")
@Tag(name = "Rule administration",
        description = "Configure rate limiting rules at runtime, without restarting the application")
public class RuleAdminController {

    private final RuleService ruleService;

    public RuleAdminController(RuleService ruleService) {
        this.ruleService = ruleService;
    }

    @PostMapping
    @Operation(
            summary = "Create a rule",
            description = """
                    Registers a rule for one component and one subscription plan and maps the
                    component to an API path. Takes effect immediately for subsequent requests.

                    Existing Redis state is NOT discarded by default: counters, timestamps and
                    buckets keep running and are reinterpreted under the new limit and window.
                    Pass `resetState=true` to start the experiment from a clean slate.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Rule created"),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "Rule already exists, or the path belongs to another component",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<RuleResponse> create(
            @Valid @RequestBody RuleRequest request,

            @Parameter(description = "Also drop every Redis state key of this component")
            @RequestParam(defaultValue = "false") boolean resetState) {

        RuleResponse response = ruleService.create(request);

        if (resetState) {
            ruleService.resetStateForComponent(response.component());
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @Operation(summary = "List all configured rules")
    @ApiResponse(responseCode = "200", description = "All rules, across every plan")
    public List<RuleResponse> findAll() {
        return ruleService.findAll();
    }

    @GetMapping("/{component}")
    @Operation(summary = "List the rules of one component",
            description = "Returns one entry per subscription plan configured for the component.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rules for the component"),
            @ApiResponse(responseCode = "404", description = "Component has no rules",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public List<RuleResponse> findByComponent(
            @Parameter(description = "Component identifier", example = "CODE_RUN")
            @PathVariable String component) {

        return ruleService.findByComponent(component);
    }

    @PutMapping
    @Operation(
            summary = "Update an existing rule",
            description = """
                    Replaces the limit, window and algorithm of an existing (component, plan)
                    rule. See the note on POST about what happens to state already in Redis.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rule updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No such rule",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public RuleResponse update(
            @Valid @RequestBody RuleRequest request,

            @Parameter(description = "Also drop every Redis state key of this component")
            @RequestParam(defaultValue = "false") boolean resetState) {

        RuleResponse response = ruleService.update(request);

        if (resetState) {
            ruleService.resetStateForComponent(response.component());
        }

        return response;
    }

    @DeleteMapping("/{component}")
    @Operation(
            summary = "Remove a component's rules",
            description = """
                    Removes the rules of the component on every plan and unmaps its API path.
                    Requests to that path then fail rule resolution until a rule is configured
                    again. Orphan Redis state expires on its own after two windows; pass
                    `resetState=true` to remove it immediately.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rules removed"),
            @ApiResponse(responseCode = "404", description = "Component has no rules",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public Map<String, Object> delete(
            @Parameter(description = "Component identifier", example = "CODE_RUN")
            @PathVariable String component,

            @Parameter(description = "Also drop every Redis state key of this component")
            @RequestParam(defaultValue = "false") boolean resetState) {

        int removed = ruleService.deleteByComponent(component, resetState);

        return Map.of(
                "component", component.trim().toUpperCase(),
                "rulesRemoved", removed,
                "stateReset", resetState,
                "message", "Component removed");
    }
}