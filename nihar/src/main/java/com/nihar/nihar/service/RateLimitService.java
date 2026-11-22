package com.nihar.nihar.service;

import com.nihar.nihar.entity.Path;
import com.nihar.nihar.entity.Request;
import com.nihar.nihar.entity.Rule;
import com.nihar.nihar.entity.User;
import com.nihar.nihar.entity.UserStatus;
import com.nihar.nihar.enums.RateLimitType;
import com.nihar.nihar.exception.InvalidRuleException;
import com.nihar.nihar.exception.RuleNotFoundException;
import com.nihar.nihar.interfaces.RateLimitingAlgorithms;
import com.nihar.nihar.managers.RateLimitFactory;
import com.nihar.nihar.managers.RateLimitStateManager;
import com.nihar.nihar.managers.RuleManager;
import com.nihar.nihar.model.RateLimitDecision;
import com.nihar.nihar.model.RateLimitVerdict;
import com.nihar.nihar.web.dto.QuotaSnapshot;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes the rate limiting pipeline for an incoming runtime request.
 *
 * <p>The flow is unchanged from the original design and simply wires the
 * existing pieces together:</p>
 * <ol>
 *   <li>build the {@code User} / {@code Request} / {@code Path} entities from
 *       the HTTP call,</li>
 *   <li>resolve the applicable {@code Rule} through {@link RuleManager},</li>
 *   <li>obtain the algorithm for that rule from {@link RateLimitFactory},</li>
 *   <li>let the algorithm's template method decide, atomically, against
 *       Redis-backed state.</li>
 * </ol>
 */
@Service
public class RateLimitService {

    private final RuleManager ruleManager;
    private final RateLimitFactory rateLimitFactory;
    private final RateLimitStateManager stateManager;

    public RateLimitService(
            RuleManager ruleManager,
            RateLimitFactory rateLimitFactory,
            RateLimitStateManager stateManager) {

        this.ruleManager = ruleManager;
        this.rateLimitFactory = rateLimitFactory;
        this.stateManager = stateManager;
    }

    /**
     * Applies rate limiting to one call and consumes quota when allowed.
     *
     * @throws RuleNotFoundException when no rule covers this method/path/plan
     */
    public RateLimitDecision check(
            String userId, String plan, String httpMethod, String apiPath) {

        Path path = buildPath(httpMethod, apiPath);
        User user = buildUser(userId, plan);

        Rule rule = resolveRule(user, path, plan);

        Request request = new Request();
        request.setUserId(userId);
        request.setPath(path);

        RateLimitingAlgorithms algorithm = selectAlgorithm(rule);

        RateLimitVerdict verdict = algorithm.evaluate(user, request);

        return new RateLimitDecision(
                userId,
                plan,
                rule.getComponent(),
                rule.getAlgorithmType(),
                verdict.allowed(),
                rule.getLimitCount(),
                rule.getWindowInSeconds(),
                verdict.remaining(),
                verdict.retryAfterSeconds(),
                RateLimitDecision.messageFor(verdict));
    }

    /**
     * Reports how much quota the caller has left for every component configured
     * on their plan, without consuming any.
     *
     * <p>The remaining counts are derived from the stored state and are
     * therefore a snapshot: a concurrent request may change them immediately
     * afterwards.</p>
     */
    public List<QuotaSnapshot> quotas(String userId, String plan) {

        List<QuotaSnapshot> snapshots = new ArrayList<>();

        Map<String, Rule> rulesForPlan =
                ruleManager.getAllRules().getOrDefault(normalisePlan(plan), Map.of());

        rulesForPlan.forEach((component, rule) -> {

            Path path = ruleManager.getPathForComponent(component);
            UserStatus status = stateManager.getUserStatus(userId, component);
            boolean exists = stateManager.hasState(userId, component);

            snapshots.add(new QuotaSnapshot(
                    component,
                    path == null ? null : path.getHttpMethod(),
                    path == null ? null : path.getUrlPattern(),
                    rule.getAlgorithmType(),
                    rule.getLimitCount(),
                    rule.getWindowInSeconds(),
                    estimateRemaining(rule, status, exists),
                    exists));
        });

        return snapshots;
    }

    // ------------------------------------------------------------------

    private Rule resolveRule(User user, Path path, String plan) {

        Rule rule = ruleManager.getApplicableRule(user, path);

        if (rule == null) {
            throw new RuleNotFoundException(
                    "No rate limit rule configured for " + path.getHttpMethod() + " "
                            + path.getUrlPattern() + " and plan '" + plan
                            + "'. Configure one via POST /api/admin/rules.");
        }

        if (rule.getAlgorithmType() == null) {
            throw new InvalidRuleException(
                    "Rule for component '" + rule.getComponent()
                            + "' has no algorithm configured");
        }

        return rule;
    }

    private RateLimitingAlgorithms selectAlgorithm(Rule rule) {
        RateLimitType type = rule.getAlgorithmType();
        return rateLimitFactory.getAlgorithm(type);
    }

    private User buildUser(String userId, String plan) {
        User user = new User();
        user.setUserId(userId);
        user.setActiveSubscriptionPlans(Set.of(normalisePlan(plan)));
        return user;
    }

    private Path buildPath(String httpMethod, String apiPath) {
        Path path = new Path();
        path.setHttpMethod(httpMethod.toUpperCase());
        path.setUrlPattern(apiPath);
        path.setComponent(ruleManager.getComponent(httpMethod, apiPath));
        return path;
    }

    /**
     * Best effort "requests still available" for the inspection endpoints. It
     * mirrors each algorithm's accounting without mutating anything.
     */
    private long estimateRemaining(Rule rule, UserStatus status, boolean exists) {

        if (!exists || rule.getAlgorithmType() == null) {
            return rule.getLimitCount();
        }

        long now = System.currentTimeMillis();
        long windowMillis = rule.getWindowInSeconds() * 1000L;

        return switch (rule.getAlgorithmType()) {

            case FIXED_WINDOW -> {
                boolean expired = status.getWindowStartTimestamp() == 0
                        || now - status.getWindowStartTimestamp() >= windowMillis;
                yield expired
                        ? rule.getLimitCount()
                        : Math.max(0, rule.getLimitCount() - status.getRequestCount());
            }

            case SLIDING_WINDOW -> {
                long windowStart = now - windowMillis;
                long active = status.getRequestTimestamps() == null
                        ? 0
                        : status.getRequestTimestamps().stream()
                                .filter(ts -> ts > windowStart)
                                .count();
                yield Math.max(0, rule.getLimitCount() - active);
            }

            case TOKEN_BUCKET -> {
                if (status.getLastRefillTimestamp() == 0) {
                    yield rule.getLimitCount();
                }
                double capacity = rule.getLimitCount();
                double refillRate = capacity / windowMillis;
                double elapsed = Math.max(0, now - status.getLastRefillTimestamp());
                double tokens = Math.min(
                        capacity, status.getAvailableTokens() + elapsed * refillRate);
                yield (long) Math.floor(tokens);
            }
        };
    }

    private String normalisePlan(String plan) {
        return plan == null ? null : plan.trim().toUpperCase();
    }
}