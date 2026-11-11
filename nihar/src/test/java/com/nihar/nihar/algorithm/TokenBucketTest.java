
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

class TokenBucketTest {

    private TokenBucket algorithm;
    private Rule rule;

    @BeforeEach
    void setUp() {
        algorithm = new TokenBucket(
                mock(RuleManager.class),
                mock(UserManager.class),
                mock(RateLimitStateManager.class)
        );

        rule = new Rule();
        rule.setLimitCount(5);
        rule.setWindowInSeconds(5);
    }

    @Test
    void shouldInitializeBucketAndConsumeOneToken() {
        UserStatus status = new UserStatus();

        assertTrue(algorithm.checkLimit(status, rule));

        assertEquals(4.0, status.getAvailableTokens(), 0.001);
        assertTrue(status.getLastRefillTimestamp() > 0);
    }

    @Test
    void shouldRejectRequestWhenNoTokensAreAvailable() {
        UserStatus status = new UserStatus();

        status.setAvailableTokens(0);
        status.setLastRefillTimestamp(System.currentTimeMillis());

        assertFalse(algorithm.checkLimit(status, rule));
        assertEquals(0.0, status.getAvailableTokens(), 0.001);
    }

    @Test
    void shouldRefillTokensBasedOnElapsedTime() {
        UserStatus status = new UserStatus();

        status.setAvailableTokens(0);
        status.setLastRefillTimestamp(
                System.currentTimeMillis() - 1_000
        );

        assertTrue(algorithm.checkLimit(status, rule));

        // Capacity 5 / 5 seconds = approximately 1 token per second.
        // The refilled token is immediately consumed.
        assertEquals(0.0, status.getAvailableTokens(), 0.01);
    }

    @Test
    void shouldNeverExceedBucketCapacity() {
        UserStatus status = new UserStatus();

        status.setAvailableTokens(2);
        status.setLastRefillTimestamp(
                System.currentTimeMillis() - 60_000
        );

        assertTrue(algorithm.checkLimit(status, rule));

        assertEquals(4.0, status.getAvailableTokens(), 0.001);
    }
}
