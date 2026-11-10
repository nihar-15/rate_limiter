package com.nihar.nihar.managers;

import com.nihar.nihar.enums.*;
import org.springframework.stereotype.Component;
import com.nihar.nihar.algorithm.FixedWindow;
import com.nihar.nihar.algorithm.SlidingWindow;
import com.nihar.nihar.algorithm.TokenBucket;


import com.nihar.nihar.interfaces.RateLimitingAlgorithms;
import com.nihar.nihar.enums.RateLimitType;


@Component
public class RateLimitFactory {

    private final RuleManager ruleManager;
    private final UserManager userManager;
    private final RateLimitStateManager stateManager;

    public RateLimitFactory(
            RuleManager ruleManager,
            UserManager userManager,
            RateLimitStateManager stateManager) {

        this.ruleManager = ruleManager;
        this.userManager = userManager;
        this.stateManager = stateManager;
    }

    public RateLimitingAlgorithms getAlgorithm(RateLimitType type) {

        switch (type) {

            case FIXED_WINDOW:
                return new FixedWindow(
                        ruleManager, userManager, stateManager);

            case SLIDING_WINDOW:
                return new SlidingWindow(
                        ruleManager, userManager, stateManager);

            case TOKEN_BUCKET:
                return new TokenBucket(
                        ruleManager, userManager, stateManager);

            default:
                throw new IllegalArgumentException(
                        "Unsupported algorithm: " + type);
        }
    }
}