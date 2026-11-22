
package com.nihar.nihar.interfaces;

import com.nihar.nihar.entity.*;
import com.nihar.nihar.managers.*;
import com.nihar.nihar.model.RateLimitVerdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RateLimitingAlgorithmsTest {

    private RuleManager ruleManager;
    private UserManager userManager;
    private RateLimitStateManager stateManager;

    private RateLimitingAlgorithms algorithm;
    private User user;
    private Request request;
    private Rule rule;
    private UserStatus status;

    /** Verdict the stubbed atomic step returns; tests override it. */
    private RateLimitVerdict atomicVerdict;

    @BeforeEach
    void setUp() {
        ruleManager = mock(RuleManager.class);
        userManager = mock(UserManager.class);
        stateManager = mock(RateLimitStateManager.class);

        atomicVerdict = RateLimitVerdict.allowed(4);

        // A simple test implementation: the algorithm-specific
        // decision is controlled by the test.
        algorithm = new RateLimitingAlgorithms(
                ruleManager, userManager, stateManager
        ) {
            @Override
            protected boolean checkLimit(UserStatus status, Rule rule) {
                return status.getRequestCount() < rule.getLimitCount();
            }

            @Override
            protected RateLimitVerdict applyAtomically(String userId, Rule rule) {
                return atomicVerdict;
            }
        };

        user = new User();
        user.setUserId("user-1");

        Path path = new Path();
        path.setHttpMethod("GET");
        path.setUrlPattern("/api/data");

        request = new Request();
        request.setPath(path);

        rule = new Rule();
        rule.setComponent("DATA");
        rule.setLimitCount(5);
        rule.setWindowInSeconds(60);

        status = new UserStatus();

        when(ruleManager.getApplicableRule(user, path)).thenReturn(rule);
        when(userManager.isBlocked("user-1", "DATA")).thenReturn(false);
        when(userManager.isRestricted("user-1", "DATA")).thenReturn(false);
        when(stateManager.getUserStatus("user-1", "DATA")).thenReturn(status);
    }

    @Test
    void shouldAllowRequestAndPersistState() {
        status.setRequestCount(1);

        assertTrue(algorithm.isAllowed(user, request));

        verify(stateManager).getUserStatus("user-1", "DATA");
        verify(stateManager).updateState("user-1", "DATA", status);
    }

    @Test
    void shouldRejectBlockedUserWithoutReadingRateLimitState() {
        when(userManager.isBlocked("user-1", "DATA")).thenReturn(true);

        assertFalse(algorithm.isAllowed(user, request));

        verify(stateManager, never()).getUserStatus(anyString(), anyString());
        verify(stateManager, never()).updateState(
                anyString(), anyString(), any()
        );
    }

    @Test
    void shouldRejectRestrictedUserWithoutReadingRateLimitState() {
        when(userManager.isRestricted("user-1", "DATA")).thenReturn(true);

        assertFalse(algorithm.isAllowed(user, request));

        verify(stateManager, never()).getUserStatus(anyString(), anyString());
        verify(stateManager, never()).updateState(
                anyString(), anyString(), any()
        );
    }

    @Test
    void shouldThrowWhenNoApplicableRuleExists() {
        when(ruleManager.getApplicableRule(user, request.getPath()))
                .thenReturn(null);

        assertThrows(
                IllegalStateException.class,
                () -> algorithm.isAllowed(user, request)
        );

        verifyNoInteractions(stateManager);
    }

    @Test
    void shouldNotPersistStateWhenAlgorithmRejectsRequest() {
        status.setRequestCount(5);

        assertFalse(algorithm.isAllowed(user, request));

        verify(stateManager).getUserStatus("user-1", "DATA");
        verify(stateManager, never()).updateState(
                anyString(), anyString(), any()
        );
    }

    // ------------------------------------------------------------------
    // evaluate(): the atomic template method used by the runtime APIs
    // ------------------------------------------------------------------

    @Test
    void evaluateShouldDelegateToTheAtomicStepWithoutReadingStateInJava() {
        atomicVerdict = RateLimitVerdict.allowed(3);

        RateLimitVerdict verdict = algorithm.evaluate(user, request);

        assertTrue(verdict.allowed());
        assertEquals(3, verdict.remaining());
        assertEquals(RateLimitVerdict.DenialReason.NONE, verdict.reason());

        // The whole read-decide-write cycle happens inside Redis.
        verify(stateManager, never()).getUserStatus(anyString(), anyString());
        verify(stateManager, never()).updateState(anyString(), anyString(), any());
    }

    @Test
    void evaluateShouldReportLimitExceededWithRetryHint() {
        atomicVerdict = RateLimitVerdict.denied(0, 2_500);

        RateLimitVerdict verdict = algorithm.evaluate(user, request);

        assertFalse(verdict.allowed());
        assertEquals(0, verdict.remaining());
        assertEquals(RateLimitVerdict.DenialReason.LIMIT_EXCEEDED, verdict.reason());
        assertEquals(3, verdict.retryAfterSeconds(), "2500ms must round up to 3s");
    }

    @Test
    void evaluateShouldRejectBlockedUserBeforeTouchingRedis() {
        when(userManager.isBlocked("user-1", "DATA")).thenReturn(true);

        RateLimitVerdict verdict = algorithm.evaluate(user, request);

        assertFalse(verdict.allowed());
        assertEquals(RateLimitVerdict.DenialReason.USER_BLOCKED, verdict.reason());
        verifyNoInteractions(stateManager);
    }

    @Test
    void evaluateShouldRejectRestrictedUserBeforeTouchingRedis() {
        when(userManager.isRestricted("user-1", "DATA")).thenReturn(true);

        RateLimitVerdict verdict = algorithm.evaluate(user, request);

        assertFalse(verdict.allowed());
        assertEquals(RateLimitVerdict.DenialReason.USER_RESTRICTED, verdict.reason());
        verifyNoInteractions(stateManager);
    }

    @Test
    void evaluateShouldThrowWhenNoApplicableRuleExists() {
        when(ruleManager.getApplicableRule(user, request.getPath()))
                .thenReturn(null);

        assertThrows(
                IllegalStateException.class,
                () -> algorithm.evaluate(user, request)
        );
    }
}
