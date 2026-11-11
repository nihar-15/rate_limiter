
package com.nihar.nihar.managers;

import com.nihar.nihar.algorithm.FixedWindow;
import com.nihar.nihar.algorithm.SlidingWindow;
import com.nihar.nihar.algorithm.TokenBucket;
import com.nihar.nihar.enums.RateLimitType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RateLimitFactoryTest {

    private RateLimitFactory factory;

    @BeforeEach
    void setUp() {
        factory = new RateLimitFactory(
                mock(RuleManager.class),
                mock(UserManager.class),
                mock(RateLimitStateManager.class)
        );
    }

    @Test
    void shouldCreateFixedWindowAlgorithm() {
        assertInstanceOf(
                FixedWindow.class,
                factory.getAlgorithm(RateLimitType.FIXED_WINDOW)
        );
    }

    @Test
    void shouldCreateSlidingWindowAlgorithm() {
        assertInstanceOf(
                SlidingWindow.class,
                factory.getAlgorithm(RateLimitType.SLIDING_WINDOW)
        );
    }

    @Test
    void shouldCreateTokenBucketAlgorithm() {
        assertInstanceOf(
                TokenBucket.class,
                factory.getAlgorithm(RateLimitType.TOKEN_BUCKET)
        );
    }

    @Test
    void shouldRejectNullAlgorithmType() {
        assertThrows(
                NullPointerException.class,
                () -> factory.getAlgorithm(null)
        );
    }
}
