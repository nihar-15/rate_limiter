
package com.nihar.nihar.interfaces;

import com.nihar.nihar.entity.*;
import com.nihar.nihar.managers.*;
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

    @BeforeEach
    void setUp() {
        ruleManager = mock(RuleManager.class);
        userManager = mock(UserManager.class);
        stateManager = mock(RateLimitStateManager.class);

        // A simple test implementation: the algorithm-specific
        // decision is controlled by the test.
        algorithm = new RateLimitingAlgorithms(
                ruleManager, userManager, stateManager
        ) {
            @Override
            protected boolean checkLimit(UserStatus status, Rule rule) {
                return status.getRequestCount() < rule.getLimitCount();
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
}
