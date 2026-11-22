package com.nihar.nihar.service;

import com.nihar.nihar.entity.Rule;
import com.nihar.nihar.entity.UserStatus;
import com.nihar.nihar.enums.RateLimitType;
import com.nihar.nihar.exception.InvalidRuleException;
import com.nihar.nihar.exception.RuleNotFoundException;
import com.nihar.nihar.interfaces.RateLimitingAlgorithms;
import com.nihar.nihar.managers.RateLimitFactory;
import com.nihar.nihar.managers.RateLimitStateManager;
import com.nihar.nihar.managers.RuleManager;
import com.nihar.nihar.managers.UserManager;
import com.nihar.nihar.model.RateLimitDecision;
import com.nihar.nihar.model.RateLimitVerdict;
import com.nihar.nihar.web.dto.QuotaSnapshot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Verifies the orchestration: rule resolution, algorithm selection through the
 * factory and the shape of the resulting decision. The algorithms themselves
 * are stubbed; their behaviour is covered by their own tests and by the Redis
 * integration tests.
 */
class RateLimitServiceTest {

    private RuleManager ruleManager;
    private RateLimitFactory factory;
    private RateLimitStateManager stateManager;
    private UserManager userManager;
    private RateLimitService service;

    private StubAlgorithm algorithm;

    /**
     * Stands in for a real algorithm: the inherited {@code evaluate} template
     * method still runs rule resolution and the block/restrict checks, only the
     * atomic Redis step is replaced.
     */
    private static final class StubAlgorithm extends RateLimitingAlgorithms {

        private RateLimitVerdict verdict = RateLimitVerdict.allowed(1);
        private int invocations;

        StubAlgorithm(RuleManager ruleManager, UserManager userManager,
                      RateLimitStateManager stateManager) {
            super(ruleManager, userManager, stateManager);
        }

        @Override
        protected boolean checkLimit(UserStatus status, Rule rule) {
            return true;
        }

        @Override
        protected RateLimitVerdict applyAtomically(String userId, Rule rule) {
            invocations++;
            return verdict;
        }
    }

    @BeforeEach
    void setUp() {
        ruleManager = new RuleManager();
        factory = mock(RateLimitFactory.class);
        stateManager = mock(RateLimitStateManager.class);
        userManager = mock(UserManager.class);
        algorithm = new StubAlgorithm(ruleManager, userManager, stateManager);

        service = new RateLimitService(ruleManager, factory, stateManager);
    }

    private void configure(
            String component, String method, String path,
            String plan, int limit, int window, RateLimitType type) {

        com.nihar.nihar.entity.Path p = new com.nihar.nihar.entity.Path();
        p.setComponent(component);
        p.setHttpMethod(method);
        p.setUrlPattern(path);
        ruleManager.mapPathToComponent(p);

        Rule rule = new Rule();
        rule.setComponent(component);
        rule.setLimitCount(limit);
        rule.setWindowInSeconds(window);
        rule.setAlgorithmType(type);
        ruleManager.addRule(plan, rule);
    }

    @Test
    void shouldSelectTheAlgorithmConfiguredOnTheResolvedRule() {
        configure("CODE_RUN", "POST", "/api/demo/code-run",
                "FREE", 5, 60, RateLimitType.FIXED_WINDOW);

        when(factory.getAlgorithm(RateLimitType.FIXED_WINDOW)).thenReturn(algorithm);
        algorithm.verdict = RateLimitVerdict.allowed(4);

        RateLimitDecision decision = service.check(
                "user-101", "FREE", "POST", "/api/demo/code-run");

        verify(factory).getAlgorithm(RateLimitType.FIXED_WINDOW);

        assertTrue(decision.allowed());
        assertEquals("user-101", decision.userId());
        assertEquals("FREE", decision.plan());
        assertEquals("CODE_RUN", decision.component());
        assertEquals(RateLimitType.FIXED_WINDOW, decision.algorithm());
        assertEquals(5, decision.limit());
        assertEquals(60, decision.windowInSeconds());
        assertEquals(4, decision.remaining());
        assertEquals(0, decision.retryAfterSeconds());
        assertEquals("Request allowed", decision.message());
    }

    @Test
    void shouldResolveADifferentRulePerSubscriptionPlan() {
        configure("CODE_RUN", "POST", "/api/demo/code-run",
                "FREE", 5, 60, RateLimitType.FIXED_WINDOW);
        configure("CODE_RUN", "POST", "/api/demo/code-run",
                "PREMIUM", 20, 60, RateLimitType.TOKEN_BUCKET);

        when(factory.getAlgorithm(any())).thenReturn(algorithm);
        algorithm.verdict = RateLimitVerdict.allowed(1);

        assertEquals(5, service.check("u", "FREE", "POST", "/api/demo/code-run").limit());
        assertEquals(20, service.check("u", "PREMIUM", "POST", "/api/demo/code-run").limit());

        verify(factory).getAlgorithm(RateLimitType.FIXED_WINDOW);
        verify(factory).getAlgorithm(RateLimitType.TOKEN_BUCKET);
    }

    @Test
    void shouldTreatThePlanCaseInsensitively() {
        configure("CODE_RUN", "POST", "/api/demo/code-run",
                "PREMIUM", 20, 60, RateLimitType.TOKEN_BUCKET);

        when(factory.getAlgorithm(any())).thenReturn(algorithm);
        algorithm.verdict = RateLimitVerdict.allowed(1);

        assertEquals(20, service.check("u", "premium", "POST", "/api/demo/code-run").limit());
    }

    @Test
    void shouldReportRejectionWithRetryAfter() {
        configure("CODE_RUN", "POST", "/api/demo/code-run",
                "FREE", 5, 60, RateLimitType.FIXED_WINDOW);

        when(factory.getAlgorithm(any())).thenReturn(algorithm);
        algorithm.verdict = RateLimitVerdict.denied(0, 12_000);

        RateLimitDecision decision = service.check(
                "user-101", "FREE", "POST", "/api/demo/code-run");

        assertFalse(decision.allowed());
        assertEquals(0, decision.remaining());
        assertEquals(12, decision.retryAfterSeconds());
        assertEquals("Rate limit exceeded", decision.message());
    }

    @Test
    void shouldFailWhenNoRuleCoversThePath() {
        RuleNotFoundException ex = assertThrows(
                RuleNotFoundException.class,
                () -> service.check("u", "FREE", "POST", "/api/demo/unknown"));

        assertTrue(ex.getMessage().contains("/api/demo/unknown"));
        verifyNoInteractions(factory);
    }

    @Test
    void shouldFailWhenThePlanHasNoRuleForThatComponent() {
        configure("CODE_RUN", "POST", "/api/demo/code-run",
                "PREMIUM", 20, 60, RateLimitType.TOKEN_BUCKET);

        assertThrows(
                RuleNotFoundException.class,
                () -> service.check("u", "FREE", "POST", "/api/demo/code-run"));
    }

    @Test
    void shouldFailWhenTheResolvedRuleHasNoAlgorithm() {
        configure("CODE_RUN", "POST", "/api/demo/code-run",
                "FREE", 5, 60, null);

        assertThrows(
                InvalidRuleException.class,
                () -> service.check("u", "FREE", "POST", "/api/demo/code-run"));
    }

    @Test
    void shouldReportFullQuotaWhenNoStateExists() {
        configure("CODE_RUN", "POST", "/api/demo/code-run",
                "FREE", 5, 60, RateLimitType.FIXED_WINDOW);

        when(stateManager.hasState(anyString(), anyString())).thenReturn(false);
        when(stateManager.getUserStatus(anyString(), anyString()))
                .thenReturn(new UserStatus());

        List<QuotaSnapshot> quotas = service.quotas("user-101", "FREE");

        assertEquals(1, quotas.size());
        QuotaSnapshot snapshot = quotas.get(0);
        assertEquals("CODE_RUN", snapshot.component());
        assertEquals(5, snapshot.remaining());
        assertFalse(snapshot.stateExists());
    }

    @Test
    void shouldComputeRemainingFixedWindowQuotaFromState() {
        configure("CODE_RUN", "POST", "/api/demo/code-run",
                "FREE", 5, 60, RateLimitType.FIXED_WINDOW);

        UserStatus status = new UserStatus();
        status.setRequestCount(3);
        status.setWindowStartTimestamp(System.currentTimeMillis());

        when(stateManager.hasState("user-101", "CODE_RUN")).thenReturn(true);
        when(stateManager.getUserStatus("user-101", "CODE_RUN")).thenReturn(status);

        assertEquals(2, service.quotas("user-101", "FREE").get(0).remaining());
    }

    @Test
    void shouldIgnoreExpiredFixedWindowStateWhenComputingQuota() {
        configure("CODE_RUN", "POST", "/api/demo/code-run",
                "FREE", 5, 60, RateLimitType.FIXED_WINDOW);

        UserStatus status = new UserStatus();
        status.setRequestCount(5);
        status.setWindowStartTimestamp(System.currentTimeMillis() - 61_000);

        when(stateManager.hasState("user-101", "CODE_RUN")).thenReturn(true);
        when(stateManager.getUserStatus("user-101", "CODE_RUN")).thenReturn(status);

        assertEquals(5, service.quotas("user-101", "FREE").get(0).remaining());
    }

    @Test
    void shouldCountOnlyInWindowTimestampsForSlidingWindowQuota() {
        configure("CODE_SUBMIT", "POST", "/api/demo/code-submit",
                "FREE", 3, 30, RateLimitType.SLIDING_WINDOW);

        long now = System.currentTimeMillis();
        UserStatus status = new UserStatus();
        status.getRequestTimestamps().add(now - 90_000);  // expired
        status.getRequestTimestamps().add(now - 1_000);   // active

        when(stateManager.hasState("user-101", "CODE_SUBMIT")).thenReturn(true);
        when(stateManager.getUserStatus("user-101", "CODE_SUBMIT")).thenReturn(status);

        assertEquals(2, service.quotas("user-101", "FREE").get(0).remaining());
    }

    @Test
    void shouldProjectTokenBucketRefillWhenComputingQuota() {
        configure("CODE_RUN", "POST", "/api/demo/code-run",
                "FREE", 10, 10, RateLimitType.TOKEN_BUCKET);

        UserStatus status = new UserStatus();
        status.setAvailableTokens(0);
        status.setLastRefillTimestamp(System.currentTimeMillis() - 3_000);

        when(stateManager.hasState("user-101", "CODE_RUN")).thenReturn(true);
        when(stateManager.getUserStatus("user-101", "CODE_RUN")).thenReturn(status);

        // 10 tokens per 10s = 1 token/s, so ~3 tokens have accrued.
        assertEquals(3, service.quotas("user-101", "FREE").get(0).remaining());
    }

    @Test
    void shouldReturnNoQuotasForAnUnknownPlan() {
        configure("CODE_RUN", "POST", "/api/demo/code-run",
                "FREE", 5, 60, RateLimitType.FIXED_WINDOW);

        assertTrue(service.quotas("user-101", "ENTERPRISE").isEmpty());
    }
}
