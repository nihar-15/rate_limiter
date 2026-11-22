
package com.nihar.nihar.interfaces;

import com.nihar.nihar.entity.*;
import com.nihar.nihar.managers.*;
import com.nihar.nihar.model.RateLimitVerdict;

import java.util.List;

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
    //
    // NOTE: this variant performs a read-modify-write against Redis from the
    // JVM and is therefore NOT safe when several requests for the same user run
    // concurrently. It is kept because it is the readable reference definition
    // of each algorithm. Serving traffic goes through evaluate(), which runs the
    // very same steps but delegates the decision to an atomic Redis script.
    public final boolean isAllowed(User user, Request request) {

        // Step 1: Resolve applicable rule
        Rule rule = ruleManager.getApplicableRule(user, request.getPath());

        if (rule == null) {
            throw new IllegalStateException(
                "No applicable rate limit rule"
            );
        }

        String component = rule.getComponent();

        // Step 2: Check user restrictions
        if (userManager.isBlocked(user.getUserId() , component)) {
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

    /**
     * Atomic template method used by the runtime APIs.
     *
     * <p>Follows exactly the same steps as {@link #isAllowed}, except that
     * steps 3 to 5 (read state, decide, write state) collapse into a single
     * atomic Redis script execution. That is what guarantees that concurrent
     * requests from one user can never exceed the configured limit.</p>
     *
     * @return the verdict, enriched with the remaining quota and a retry hint
     */
    public final RateLimitVerdict evaluate(User user, Request request) {

        // Step 1: Resolve applicable rule
        Rule rule = ruleManager.getApplicableRule(user, request.getPath());

        if (rule == null) {
            throw new IllegalStateException(
                "No applicable rate limit rule"
            );
        }

        String component = rule.getComponent();

        // Step 2: Check user restrictions
        if (userManager.isBlocked(user.getUserId(), component)) {
            return RateLimitVerdict.blocked();
        }

        if (userManager.isRestricted(user.getUserId(), component)) {
            return RateLimitVerdict.restricted();
        }

        // Steps 3-5: atomic read, decide and write inside Redis
        return applyAtomically(user.getUserId(), rule);
    }

    // Algorithm-specific decision
    protected abstract boolean checkLimit(
        UserStatus status, Rule rule
    );

    /**
     * Algorithm-specific atomic decision, normally implemented by invoking the
     * algorithm's Lua script through the state manager.
     */
    protected abstract RateLimitVerdict applyAtomically(
        String userId, Rule rule
    );

    /** Maps the {allowed, remaining, retryAfterMillis} script reply to a verdict. */
    protected final RateLimitVerdict toVerdict(List<Long> scriptResult) {

        boolean allowed = scriptResult.get(0) == 1L;
        long remaining = scriptResult.get(1);
        long retryAfterMillis = scriptResult.get(2);

        return allowed
                ? RateLimitVerdict.allowed(remaining)
                : RateLimitVerdict.denied(remaining, retryAfterMillis);
    }

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