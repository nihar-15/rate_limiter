package com.nihar.nihar.web.dto;

import com.nihar.nihar.enums.RateLimitType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Rule definition accepted by the admin APIs. A rule is uniquely identified by
 * the pair (component, plan); the path and method describe which API the
 * component stands for.
 */
@Schema(
        name = "RuleRequest",
        description = "Rate limiting rule for one component and one subscription plan",
        example = """
                {
                  "component": "CODE_RUN",
                  "httpMethod": "POST",
                  "apiPath": "/api/demo/code-run",
                  "plan": "FREE",
                  "limit": 5,
                  "windowInSeconds": 60,
                  "algorithm": "FIXED_WINDOW"
                }
                """)
public class RuleRequest {

    @Schema(description = "Logical component the API belongs to", example = "CODE_RUN")
    @NotBlank(message = "component must not be blank")
    @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}",
            message = "component may only contain letters, digits, '_' and '-'")
    private String component;

    @Schema(description = "HTTP method of the API", example = "POST",
            allowableValues = {"GET", "POST", "PUT", "PATCH", "DELETE"})
    @NotBlank(message = "httpMethod must not be blank")
    @Pattern(regexp = "(?i)GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS",
            message = "httpMethod must be a valid HTTP method")
    private String httpMethod;

    @Schema(description = "Exact API path the rule applies to", example = "/api/demo/code-run")
    @NotBlank(message = "apiPath must not be blank")
    @Pattern(regexp = "/.*", message = "apiPath must start with '/'")
    private String apiPath;

    @Schema(description = "Subscription plan this rule applies to", example = "FREE")
    @NotBlank(message = "plan must not be blank")
    @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}",
            message = "plan may only contain letters, digits, '_' and '-'")
    private String plan;

    @Schema(description = "Maximum number of requests allowed in the window", example = "5")
    @NotNull(message = "limit must be provided")
    @Min(value = 1, message = "limit must be at least 1")
    @Max(value = 1_000_000, message = "limit must not exceed 1000000")
    private Integer limit;

    @Schema(description = "Length of the rate limiting window in seconds", example = "60")
    @NotNull(message = "windowInSeconds must be provided")
    @Min(value = 1, message = "windowInSeconds must be at least 1")
    @Max(value = 86_400, message = "windowInSeconds must not exceed 86400")
    private Integer windowInSeconds;

    @Schema(description = "Rate limiting algorithm to apply", example = "FIXED_WINDOW")
    @NotNull(message = "algorithm must be one of FIXED_WINDOW, SLIDING_WINDOW, TOKEN_BUCKET")
    private RateLimitType algorithm;

    public String getComponent() {
        return component;
    }

    public void setComponent(String component) {
        this.component = component;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getApiPath() {
        return apiPath;
    }

    public void setApiPath(String apiPath) {
        this.apiPath = apiPath;
    }

    public String getPlan() {
        return plan;
    }

    public void setPlan(String plan) {
        this.plan = plan;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public Integer getWindowInSeconds() {
        return windowInSeconds;
    }

    public void setWindowInSeconds(Integer windowInSeconds) {
        this.windowInSeconds = windowInSeconds;
    }

    public RateLimitType getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(RateLimitType algorithm) {
        this.algorithm = algorithm;
    }
}
