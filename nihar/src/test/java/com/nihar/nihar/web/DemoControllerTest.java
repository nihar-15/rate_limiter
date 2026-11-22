package com.nihar.nihar.web;

import com.nihar.nihar.config.RateLimiterProperties;
import com.nihar.nihar.config.SecurityConfig;
import com.nihar.nihar.enums.RateLimitType;
import com.nihar.nihar.exception.RuleNotFoundException;
import com.nihar.nihar.model.RateLimitDecision;
import com.nihar.nihar.service.RateLimitService;
import com.nihar.nihar.web.controller.DemoController;
import com.nihar.nihar.web.dto.QuotaSnapshot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DemoController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class,
        DemoControllerTest.PropertiesConfig.class})
@ActiveProfiles("local")
class DemoControllerTest {

    @TestConfiguration
    static class PropertiesConfig {
        @Bean
        RateLimiterProperties rateLimiterProperties() {
            return new RateLimiterProperties();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RateLimitService rateLimitService;

    private RateLimitDecision allowed() {
        return new RateLimitDecision("user-101", "FREE", "CODE_RUN",
                RateLimitType.FIXED_WINDOW, true, 5, 60, 4, 0,
                RateLimitDecision.ALLOWED_MESSAGE);
    }

    private RateLimitDecision rejected() {
        return new RateLimitDecision("user-101", "FREE", "CODE_RUN",
                RateLimitType.FIXED_WINDOW, false, 5, 60, 0, 37,
                RateLimitDecision.LIMIT_EXCEEDED_MESSAGE);
    }

    @Test
    void shouldReturn200AndTheDocumentedBodyWhenAllowed() throws Exception {
        when(rateLimitService.check("user-101", "FREE", "POST", "/api/demo/code-run"))
                .thenReturn(allowed());

        mockMvc.perform(post("/api/demo/code-run")
                        .header("X-User-Id", "user-101")
                        .header("X-Subscription-Plan", "FREE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user-101"))
                .andExpect(jsonPath("$.component").value("CODE_RUN"))
                .andExpect(jsonPath("$.algorithm").value("FIXED_WINDOW"))
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.limit").value(5))
                .andExpect(jsonPath("$.windowInSeconds").value(60))
                .andExpect(jsonPath("$.message").value("Request allowed"))
                .andExpect(jsonPath("$.remaining").value(4))
                .andExpect(header().string("X-RateLimit-Limit", "5"))
                .andExpect(header().string("X-RateLimit-Remaining", "4"))
                .andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    void shouldReturn429WithRetryAfterWhenRejected() throws Exception {
        when(rateLimitService.check(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(rejected());

        mockMvc.perform(post("/api/demo/code-run")
                        .header("X-User-Id", "user-101"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.message").value("Rate limit exceeded"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(37))
                .andExpect(header().string("Retry-After", "37"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"));
    }

    @Test
    void shouldFallBackToConfiguredDefaultsWhenHeadersAreAbsent() throws Exception {
        when(rateLimitService.check("anonymous", "FREE", "POST", "/api/demo/code-run"))
                .thenReturn(allowed());

        mockMvc.perform(post("/api/demo/code-run"))
                .andExpect(status().isOk());

        verify(rateLimitService).check("anonymous", "FREE", "POST", "/api/demo/code-run");
    }

    @Test
    void shouldPassTheRequestedPathAndMethodThrough() throws Exception {
        when(rateLimitService.check("user-9", "PREMIUM", "POST", "/api/demo/code-submit"))
                .thenReturn(allowed());

        mockMvc.perform(post("/api/demo/code-submit")
                        .header("X-User-Id", "user-9")
                        .header("X-Subscription-Plan", "PREMIUM"))
                .andExpect(status().isOk());

        verify(rateLimitService).check("user-9", "PREMIUM", "POST", "/api/demo/code-submit");
    }

    @Test
    void shouldReturn404WhenNoRuleIsConfigured() throws Exception {
        when(rateLimitService.check(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuleNotFoundException("No rate limit rule configured"));

        mockMvc.perform(post("/api/demo/code-run"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RULE_NOT_FOUND"));
    }

    @Test
    void statusShouldReturnTheVerdictTogetherWithQuotas() throws Exception {
        when(rateLimitService.check("user-101", "FREE", "GET", "/api/demo/status"))
                .thenReturn(allowed());
        when(rateLimitService.quotas("user-101", "FREE")).thenReturn(List.of(
                new QuotaSnapshot("CODE_RUN", "POST", "/api/demo/code-run",
                        RateLimitType.FIXED_WINDOW, 5, 60, 2, true)));

        mockMvc.perform(get("/api/demo/status").header("X-User-Id", "user-101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rateLimit.allowed").value(true))
                .andExpect(jsonPath("$.quotas.length()").value(1))
                .andExpect(jsonPath("$.quotas[0].component").value("CODE_RUN"))
                .andExpect(jsonPath("$.quotas[0].remaining").value(2));
    }

    @Test
    void statusShouldAlsoBeRateLimited() throws Exception {
        when(rateLimitService.check(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(rejected());
        when(rateLimitService.quotas(anyString(), anyString())).thenReturn(List.of());

        mockMvc.perform(get("/api/demo/status").header("X-User-Id", "user-101"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.rateLimit.allowed").value(false));
    }

    @Test
    void shouldTrimWhitespaceInIdentityHeaders() throws Exception {
        when(rateLimitService.check("user-7", "PREMIUM", "POST", "/api/demo/code-run"))
                .thenReturn(allowed());

        mockMvc.perform(post("/api/demo/code-run")
                        .header("X-User-Id", "  user-7  ")
                        .header("X-Subscription-Plan", " PREMIUM "))
                .andExpect(status().isOk());

        verify(rateLimitService).check("user-7", "PREMIUM", "POST", "/api/demo/code-run");
    }
}
