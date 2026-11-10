
package com.nihar.nihar.interfaces;

import com.nihar.nihar.entity.*;
import com.nihar.nihar.managers.*;

public abstract class RateLimitingAlgorithms {

    protected final RuleManager ruleManager;
    protected final UserManager userManager;
    protected final RateLimitStateManager stateManager;

    public RateLimitingAlgorithms(
            RuleManager ruleManager,
            UserManager userManager,
            RateLimitStateManager stateManager) {

        this.ruleManager = ruleManager;
        this.userManager = userManager;
        this.stateManager = stateManager;
    }

    // Template Method
    public final boolean isAllowed(User user, Request request) {

        // Step 1: Resolve applicable rule
        Rule rule = ruleManager.getApplicableRule(user, request);

        if (rule == null) {
            throw new IllegalStateException(
                "No applicable rate limit rule"
            );
        }

        String component = rule.getComponent();

        // Step 2: Check user restrictions
        if (userManager.isBlocked(user.getUserId())) {
            return false;
        }

        if (userManager.isRestricted(user.getUserId(), component)) {
            return false;
        }

        // Step 3: Fetch current algorithm state
       UserStatus status = stateManager.getUserStatus(user.getUserId(),rule.getComponent());

        // Step 4: Algorithm-specific decision
        boolean allowed = checkLimit(status, rule);

        // Step 5: Update state
        if (allowed) {
            updateState(user, rule, status);
        }

        return allowed;
    }

    // Algorithm-specific decision
    protected abstract boolean checkLimit(
        UserStatus status, Rule rule
    );

    // Common state update hook
    protected void updateState(
            User user, Rule rule, UserStatus status) {

        stateManager.updateState(
            user.getUserId(),
            rule.getComponent(),
            status
        );
    }
}
