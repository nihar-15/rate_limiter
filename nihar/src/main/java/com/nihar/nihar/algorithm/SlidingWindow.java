package com.nihar.nihar.algorithm;

import com.nihar.nihar.entity.*;
import com.nihar.nihar.managers.*;
import com.nihar.nihar.interfaces.*;
import com.nihar.nihar.model.RateLimitVerdict;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

public class SlidingWindow extends RateLimitingAlgorithms {

    /** Atomic Redis-side counterpart of {@link #checkLimit}. */
    private static final RedisScript<List> SCRIPT = RedisScript.of(
            new ClassPathResource("scripts/sliding_window.lua"), List.class);

    public SlidingWindow(
            RuleManager ruleManager,
            UserManager userManager,
            RateLimitStateManager stateManager) {

        super(ruleManager, userManager, stateManager);
    }

    @Override
    protected RateLimitVerdict applyAtomically(String userId, Rule rule) {

        return toVerdict(stateManager.execute(
                SCRIPT,
                userId,
                rule.getComponent(),
                rule.getLimitCount(),
                rule.getWindowInSeconds()
        ));
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