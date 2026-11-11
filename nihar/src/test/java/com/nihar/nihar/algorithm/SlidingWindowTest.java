
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

class SlidingWindowTest {

    private SlidingWindow algorithm;
    private Rule rule;

    @BeforeEach
    void setUp() {
        algorithm = new SlidingWindow(
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

        assertEquals(3, status.getRequestTimestamps().size());
    }

    @Test
    void shouldRejectRequestWhenWindowIsFull() {
        UserStatus status = new UserStatus();

        long now = System.currentTimeMillis();

        status.getRequestTimestamps().add(now - 10_000);
        status.getRequestTimestamps().add(now - 5_000);
        status.getRequestTimestamps().add(now - 1_000);

        assertFalse(algorithm.checkLimit(status, rule));
        assertEquals(3, status.getRequestTimestamps().size());
    }

    @Test
    void shouldRemoveExpiredRequestsBeforeCheckingLimit() {
        UserStatus status = new UserStatus();

        long now = System.currentTimeMillis();

        status.getRequestTimestamps().add(now - 120_000);
        status.getRequestTimestamps().add(now - 90_000);
        status.getRequestTimestamps().add(now - 1_000);

        assertTrue(algorithm.checkLimit(status, rule));

        // Two expired entries removed, one current entry retained,
        // and the new request added.
        assertEquals(2, status.getRequestTimestamps().size());
    }

    @Test
    void shouldRemoveTimestampExactlyAtWindowBoundary() {
        UserStatus status = new UserStatus();

        long now = System.currentTimeMillis();
        status.getRequestTimestamps().add(now - 60_000);

        assertTrue(algorithm.checkLimit(status, rule));
        assertEquals(1, status.getRequestTimestamps().size());
    }
}
