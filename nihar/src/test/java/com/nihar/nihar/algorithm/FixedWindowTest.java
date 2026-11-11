
package com.nihar.nihar.algorithm;

import com.nihar.nihar.entity.Rule;
import com.nihar.nihar.entity.UserStatus;
import com.nihar.nihar.managers.RateLimitStateManager;
import com.nihar.nihar.managers.RuleManager;
import com.nihar.nihar.managers.UserManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class FixedWindowTest {

    private FixedWindow algorithm;
    private Rule rule;

    @BeforeEach
    void setUp() {
        algorithm = new FixedWindow(
                mock(RuleManager.class),
                mock(UserManager.class),
                mock(RateLimitStateManager.class)
        );

        rule = new Rule();
        rule.setLimitCount(3);
        rule.setWindowInSeconds(60);
    }

    @Test
    void shouldAllowRequestsUntilLimitIsReached() {
        UserStatus status = new UserStatus();

        assertTrue(algorithm.checkLimit(status, rule));
        assertTrue(algorithm.checkLimit(status, rule));
        assertTrue(algorithm.checkLimit(status, rule));

        assertEquals(3, status.getRequestCount());
    }

    @Test
    void shouldRejectRequestAfterLimitIsReached() {
        UserStatus status = new UserStatus();

        assertTrue(algorithm.checkLimit(status, rule));
        assertTrue(algorithm.checkLimit(status, rule));
        assertTrue(algorithm.checkLimit(status, rule));

        assertFalse(algorithm.checkLimit(status, rule));
        assertEquals(3, status.getRequestCount());
    }

    @Test
    void shouldResetCounterWhenWindowExpires() {
        UserStatus status = new UserStatus();

        status.setRequestCount(3);
        status.setWindowStartTimestamp(
                System.currentTimeMillis() - 61_000
        );

        assertTrue(algorithm.checkLimit(status, rule));

        assertEquals(1, status.getRequestCount());
        assertTrue(status.getWindowStartTimestamp() > 0);
    }

    @Test
    void shouldNotResetCounterBeforeWindowExpires() {
        UserStatus status = new UserStatus();

        status.setRequestCount(3);
        status.setWindowStartTimestamp(
                System.currentTimeMillis() - 30_000
        );

        assertFalse(algorithm.checkLimit(status, rule));
        assertEquals(3, status.getRequestCount());
    }
}
