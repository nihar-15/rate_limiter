package com.nihar.nihar.config;

import com.nihar.nihar.enums.RateLimitType;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Externalised rate limiter configuration, bound from {@code ratelimiter.*}.
 *
 * <p>Rules declared here are only a seed: they are loaded into the
 * {@code RuleManager} at startup and can be changed at runtime through the
 * admin APIs without restarting the application.</p>
 */
@ConfigurationProperties(prefix = "ratelimiter")
public class RateLimiterProperties {

    /** Rules applied at startup. */
    private List<RuleDefinition> rules = new ArrayList<>();

    /** Defaults used when a request does not identify itself. */
    private Identity defaults = new Identity();

    /** Switches for the playground administration APIs. */
    private Admin admin = new Admin();

    public List<RuleDefinition> getRules() {
        return rules;
    }

    public void setRules(List<RuleDefinition> rules) {
        this.rules = rules;
    }

    public Identity getDefaults() {
        return defaults;
    }

    public void setDefaults(Identity defaults) {
        this.defaults = defaults;
    }

    public Admin getAdmin() {
        return admin;
    }

    public void setAdmin(Admin admin) {
        this.admin = admin;
    }

    /** A single seed rule. Mirrors {@code RuleRequest}. */
    public static class RuleDefinition {

        private String component;
        private String httpMethod;
        private String apiPath;
        private String plan;
        private int limit;
        private int windowInSeconds;
        private RateLimitType algorithm;

        public String getComponent() {
            return component;
        }

        public void setComponent(String component) {
            this.component = component;
        }

        public String getHttpMethod() {
            return httpMethod;
        }

        public void setHttpMethod(String httpMethod) {
            this.httpMethod = httpMethod;
        }

        public String getApiPath() {
            return apiPath;
        }

        public void setApiPath(String apiPath) {
            this.apiPath = apiPath;
        }

        public String getPlan() {
            return plan;
        }

        public void setPlan(String plan) {
            this.plan = plan;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public int getWindowInSeconds() {
            return windowInSeconds;
        }

        public void setWindowInSeconds(int windowInSeconds) {
            this.windowInSeconds = windowInSeconds;
        }

        public RateLimitType getAlgorithm() {
            return algorithm;
        }

        public void setAlgorithm(RateLimitType algorithm) {
            this.algorithm = algorithm;
        }
    }

    /** Fallback identity for demo calls that omit the identification headers. */
    public static class Identity {

        /** Used when {@code X-User-Id} is absent. */
        private String userId = "anonymous";

        /** Used when {@code X-Subscription-Plan} is absent. */
        private String plan = "FREE";

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getPlan() {
            return plan;
        }

        public void setPlan(String plan) {
            this.plan = plan;
        }
    }

    /**
     * Administration switches. Both default to {@code false} so that a
     * deployment that forgets to configure anything does not expose per-user
     * state; the {@code local} profile turns them on for the playground.
     */
    public static class Admin {

        /** Enables GET/DELETE of per-user Redis state. */
        private boolean stateInspectionEnabled = false;

        /** Enables wiping every rate limit key at once. */
        private boolean allowResetAll = false;

        public boolean isStateInspectionEnabled() {
            return stateInspectionEnabled;
        }

        public void setStateInspectionEnabled(boolean stateInspectionEnabled) {
            this.stateInspectionEnabled = stateInspectionEnabled;
        }

        public boolean isAllowResetAll() {
            return allowResetAll;
        }

        public void setAllowResetAll(boolean allowResetAll) {
            this.allowResetAll = allowResetAll;
        }
    }
}