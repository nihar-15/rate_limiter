package com.nihar.nihar.algorithm;


import com.nihar.nihar.entity.*;
import com.nihar.nihar.managers.*;
import com.nihar.nihar.interfaces.*;

/*Concept: Allow at most N requests within a fixed time interval. */
public class FixedWindow extends RateLimitingAlgorithms {

    public FixedWindow(
            RuleManager ruleManager,
            UserManager userManager,
            RateLimitStateManager stateManager) {

        super(ruleManager, userManager, stateManager);
    }

    @Override
    protected boolean checkLimit(UserStatus status, Rule rule) {

        long now = System.currentTimeMillis();

        long windowMillis = rule.getWindowInSeconds() * 1000L;

        // First request or expired window
        if (status.getWindowStartTimestamp() == 0 ||
            now - status.getWindowStartTimestamp() >= windowMillis) {

            status.setWindowStartTimestamp(now);
            status.setRequestCount(0);
        }

        // Check limit
        if (status.getRequestCount() >= rule.getLimitCount()) {
            return false;
        }

        // Consume request
        status.setRequestCount(status.getRequestCount() + 1);

        return true;
    }
}