package com.nihar.nihar.algorithm;

import com.nihar.nihar.entity.*;
import com.nihar.nihar.managers.*;
import com.nihar.nihar.interfaces.*;

public class TokenBucket extends RateLimitingAlgorithms {

    public TokenBucket(
            RuleManager ruleManager,
            UserManager userManager,
            RateLimitStateManager stateManager) {

        super(ruleManager, userManager, stateManager);
    }

    @Override
    protected boolean checkLimit(UserStatus status, Rule rule) {

        long now = System.currentTimeMillis();

        double capacity = rule.getLimitCount();

        long windowMillis = rule.getWindowInSeconds() * 1000L;

        // Initialize bucket
        if (status.getLastRefillTimestamp() == 0) {

            status.setAvailableTokens(capacity);
            status.setLastRefillTimestamp(now);
        }

        // Calculate elapsed time
        long elapsed = Math.max(
                0,
                now - status.getLastRefillTimestamp()
        );

        // Calculate refill rate
        double refillRate = capacity / windowMillis;

        // Refill tokens
        double tokensToAdd = elapsed * refillRate;

        double updatedTokens = Math.min(
                capacity,
                status.getAvailableTokens() + tokensToAdd
        );

        status.setAvailableTokens(updatedTokens);

        status.setLastRefillTimestamp(now);

        // Check availability
        if (status.getAvailableTokens() < 1.0) {
            return false;
        }

        // Consume one token
        status.setAvailableTokens(
                status.getAvailableTokens() - 1
        );

        return true;
    }
}