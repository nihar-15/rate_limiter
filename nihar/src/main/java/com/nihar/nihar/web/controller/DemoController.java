package com.nihar.nihar.web.controller;

import com.nihar.nihar.config.RateLimiterProperties;
import com.nihar.nihar.model.RateLimitDecision;
import com.nihar.nihar.service.RateLimitService;
import com.nihar.nihar.web.dto.ApiError;
import com.nihar.nihar.web.dto.DemoStatusResponse;
import com.nihar.nihar.web.dto.RateLimitResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime playground endpoints.
 *
 * <p>Every call here goes through the real pipeline: rule resolution, algorithm
 * selection via the factory and an atomic decision against Redis state. The
 * controller holds no rule of its own; which limit applies is entirely decided
 * by the configured rules, which are keyed by the HTTP method and path of the
 * incoming request.</p>
 */
@RestController
@RequestMapping("/api/demo")
@Tag(name = "Demo runtime",
        description = "Rate limited endpoints used to observe the limiter at runtime")
public class DemoController {

    /** Header carrying the caller identity. */
    public static final String USER_HEADER = "X-User-Id";

    /** Header carrying the caller's subscription plan. */
    public static final String PLAN_HEADER = "X-Subscription-Plan";

    private static final String LIMIT_HEADER = "X-RateLimit-Limit";
    private static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    private static final String RESET_HEADER = "X-RateLimit-Reset";

    private final RateLimitService rateLimitService;
    private final RateLimiterProperties properties;

    public DemoController(
            RateLimitService rateLimitService,
            RateLimiterProperties properties) {

        this.rateLimitService = rateLimitService;
        this.properties = properties;
    }

    @PostMapping("/code-run")
    @Operation(
            summary = "Run code (rate limited)",
            description = """
                    Consumes one unit of quota for the component mapped to
                    `POST /api/demo/code-run`.

                    Identify the caller with `X-User-Id` and `X-Subscription-Plan`. When a
                    header is omitted the configured defaults apply
                    (`ratelimiter.defaults.user-id` = `anonymous`,
                    `ratelimiter.defaults.plan` = `FREE`).""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Request allowed"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded",
                    content = @Content(schema = @Schema(implementation = RateLimitResponse.class))),
            @ApiResponse(responseCode = "404", description = "No rule configured for this path and plan",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<RateLimitResponse> codeRun(
            @Parameter(description = "Caller identity", example = "user-101")
            @RequestHeader(value = USER_HEADER, required = false) String userId,

            @Parameter(description = "Subscription plan", example = "FREE")
            @RequestHeader(value = PLAN_HEADER, required = false) String plan,

            HttpServletRequest httpRequest) {

        return execute(userId, plan, httpRequest);
    }

    @PostMapping("/code-submit")
    @Operation(
            summary = "Submit code (rate limited)",
            description = """
                    Consumes one unit of quota for the component mapped to
                    `POST /api/demo/code-submit`. Configure it with a different algorithm from
                    code-run to compare their behaviour side by side.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Request allowed"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded",
                    content = @Content(schema = @Schema(implementation = RateLimitResponse.class))),
            @ApiResponse(responseCode = "404", description = "No rule configured for this path and plan",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<RateLimitResponse> codeSubmit(
            @Parameter(description = "Caller identity", example = "user-101")
            @RequestHeader(value = USER_HEADER, required = false) String userId,

            @Parameter(description = "Subscription plan", example = "FREE")
            @RequestHeader(value = PLAN_HEADER, required = false) String plan,

            HttpServletRequest httpRequest) {

        return execute(userId, plan, httpRequest);
    }

    @GetMapping("/status")
    @Operation(
            summary = "Status check (rate limited) plus the caller's quotas",
            description = """
                    Rate limits the status call itself through the component mapped to
                    `GET /api/demo/status`, and additionally reports how much quota the caller
                    has left on every component of their plan. The quota snapshot is read-only
                    and consumes nothing.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Request allowed"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded",
                    content = @Content(schema = @Schema(implementation = DemoStatusResponse.class))),
            @ApiResponse(responseCode = "404", description = "No rule configured for this path and plan",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<DemoStatusResponse> status(
            @Parameter(description = "Caller identity", example = "user-101")
            @RequestHeader(value = USER_HEADER, required = false) String userId,

            @Parameter(description = "Subscription plan", example = "FREE")
            @RequestHeader(value = PLAN_HEADER, required = false) String plan,

            HttpServletRequest httpRequest) {

        String resolvedUser = resolveUser(userId);
        String resolvedPlan = resolvePlan(plan);

        RateLimitDecision decision = rateLimitService.check(
                resolvedUser,
                resolvedPlan,
                httpRequest.getMethod(),
                httpRequest.getRequestURI());

        DemoStatusResponse body = new DemoStatusResponse(
                RateLimitResponse.from(decision),
                rateLimitService.quotas(resolvedUser, resolvedPlan));

        return respond(decision, body);
    }

    // ------------------------------------------------------------------

    private ResponseEntity<RateLimitResponse> execute(
            String userId, String plan, HttpServletRequest httpRequest) {

        RateLimitDecision decision = rateLimitService.check(
                resolveUser(userId),
                resolvePlan(plan),
                httpRequest.getMethod(),
                httpRequest.getRequestURI());

        return respond(decision, RateLimitResponse.from(decision));
    }

    /** 200 when allowed, 429 when not, with the standard rate limit headers. */
    private <T> ResponseEntity<T> respond(RateLimitDecision decision, T body) {

        ResponseEntity.BodyBuilder builder = ResponseEntity
                .status(decision.allowed() ? HttpStatus.OK : HttpStatus.TOO_MANY_REQUESTS)
                .header(LIMIT_HEADER, String.valueOf(decision.limit()))
                .header(REMAINING_HEADER, String.valueOf(decision.remaining()))
                .header(RESET_HEADER, String.valueOf(decision.retryAfterSeconds()));

        if (!decision.allowed() && decision.retryAfterSeconds() > 0) {
            builder.header(HttpHeaders.RETRY_AFTER,
                    String.valueOf(decision.retryAfterSeconds()));
        }

        return builder.body(body);
    }

    private String resolveUser(String userId) {
        return (userId == null || userId.isBlank())
                ? properties.getDefaults().getUserId()
                : userId.trim();
    }

    private String resolvePlan(String plan) {
        return (plan == null || plan.isBlank())
                ? properties.getDefaults().getPlan()
                : plan.trim();
    }
}