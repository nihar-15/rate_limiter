package com.nihar.nihar.algorithm;

import com.nihar.nihar.entity.*;
import com.nihar.nihar.managers.*;
import com.nihar.nihar.interfaces.*;
import java.util.List;

public class SlidingWindow extends RateLimitingAlgorithms {

    public SlidingWindow(
            RuleManager ruleManager,
            UserManager userManager,
            RateLimitStateManager stateManager) {

        super(ruleManager, userManager, stateManager);
    }

    @Override
    protected boolean checkLimit(UserStatus status, Rule rule) {

        long now = System.currentTimeMillis();

        long windowMillis = rule.getWindowInSeconds() * 1000L;

        List<Long> timestamps = status.getRequestTimestamps();

        // Remove expired requests
        long windowStart = now - windowMillis;

        timestamps.removeIf(timestamp -> timestamp <= windowStart);

        // Check limit
        if (timestamps.size() >= rule.getLimitCount()) {
            return false;
        }

        // Record current request
        timestamps.add(now);

        return true;
    }
}