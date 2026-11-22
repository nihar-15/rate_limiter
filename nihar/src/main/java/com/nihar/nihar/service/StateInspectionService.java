package com.nihar.nihar.service;

import com.nihar.nihar.config.RateLimiterProperties;
import com.nihar.nihar.entity.UserStatus;
import com.nihar.nihar.exception.AdminApiDisabledException;
import com.nihar.nihar.managers.RateLimitStateManager;
import com.nihar.nihar.web.dto.StateResetResponse;
import com.nihar.nihar.web.dto.StateResponse;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Read and reset access to the Redis-backed rate limiting state.
 *
 * <p>Both capabilities are gated by {@code ratelimiter.admin.*}, which defaults
 * to off. Per-user state is only exposed when inspection is explicitly enabled
 * (the {@code local} profile does so for the playground).</p>
 */
@Service
public class StateInspectionService {

    private final RateLimitStateManager stateManager;
    private final RateLimiterProperties properties;

    public StateInspectionService(
            RateLimitStateManager stateManager,
            RateLimiterProperties properties) {

        this.stateManager = stateManager;
        this.properties = properties;
    }

    public StateResponse getState(String userId, String component) {

        requireStateInspectionEnabled();

        String normalised = component.trim().toUpperCase();

        boolean exists = stateManager.hasState(userId, normalised);
        UserStatus status = stateManager.getUserStatus(userId, normalised);

        return new StateResponse(
                userId,
                normalised,
                exists,
                RateLimitStateManager.KEY_PREFIX + userId + ":" + normalised,
                stateManager.getTimeToLiveSeconds(userId, normalised),
                status.getRequestCount(),
                status.getWindowStartTimestamp(),
                status.getRequestTimestamps() == null
                        ? List.of()
                        : List.copyOf(status.getRequestTimestamps()),
                status.getAvailableTokens(),
                status.getLastRefillTimestamp());
    }

    public StateResetResponse resetState(String userId, String component) {

        requireStateInspectionEnabled();

        String normalised = component.trim().toUpperCase();
        boolean removed = stateManager.resetState(userId, normalised);

        return new StateResetResponse(
                userId,
                normalised,
                removed ? 1 : 0,
                removed
                        ? "State reset"
                        : "No state was stored for this user and component");
    }

    /**
     * Wipes every rate limit key. Playground only: enabled by
     * {@code ratelimiter.admin.allow-reset-all}, which the {@code local}
     * profile sets and no other profile does.
     */
    public StateResetResponse resetAll() {

        if (!properties.getAdmin().isAllowResetAll()) {
            throw new AdminApiDisabledException(
                    "Resetting all playground state is disabled. "
                            + "Set ratelimiter.admin.allow-reset-all=true "
                            + "(enabled by default in the 'local' profile).");
        }

        long removed = stateManager.resetAllState();

        return new StateResetResponse(
                null, null, removed, "All rate limit state reset");
    }

    private void requireStateInspectionEnabled() {
        if (!properties.getAdmin().isStateInspectionEnabled()) {
            throw new AdminApiDisabledException(
                    "State inspection APIs are disabled. "
                            + "Set ratelimiter.admin.state-inspection-enabled=true "
                            + "(enabled by default in the 'local' profile).");
        }
    }
}