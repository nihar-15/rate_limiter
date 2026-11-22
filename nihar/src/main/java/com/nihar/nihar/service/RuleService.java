package com.nihar.nihar.service;

import com.nihar.nihar.entity.Path;
import com.nihar.nihar.entity.Rule;
import com.nihar.nihar.exception.InvalidRuleException;
import com.nihar.nihar.exception.RuleConflictException;
import com.nihar.nihar.exception.RuleNotFoundException;
import com.nihar.nihar.managers.RateLimitStateManager;
import com.nihar.nihar.managers.RuleManager;
import com.nihar.nihar.web.dto.RuleRequest;
import com.nihar.nihar.web.dto.RuleResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Owns the lifecycle of rate limiting rules.
 *
 * <p>All mutations go through the existing {@link RuleManager}; this service
 * adds validation, conflict detection, DTO mapping and the optional reset of
 * Redis state that accompanies a rule change.</p>
 */
@Service
public class RuleService {

    private static final Logger log = LoggerFactory.getLogger(RuleService.class);

    private final RuleManager ruleManager;
    private final RateLimitStateManager stateManager;

    public RuleService(RuleManager ruleManager, RateLimitStateManager stateManager) {
        this.ruleManager = ruleManager;
        this.stateManager = stateManager;
    }

    /**
     * Registers a brand new rule.
     *
     * @throws RuleConflictException if (component, plan) already exists, or if
     *                               the path is already owned by another
     *                               component, or if the component is already
     *                               bound to a different path
     */
    public RuleResponse create(RuleRequest request) {

        String component = normaliseComponent(request.getComponent());
        String plan = normalisePlan(request.getPlan());

        if (ruleManager.getRule(plan, component) != null) {
            throw new RuleConflictException(
                    "A rule already exists for component '" + component
                            + "' and plan '" + plan + "'. Use PUT /api/admin/rules to update it.");
        }

        return apply(request, component, plan);
    }

    /**
     * Replaces an existing rule.
     *
     * @throws RuleNotFoundException if no rule exists for (component, plan)
     */
    public RuleResponse update(RuleRequest request) {

        String component = normaliseComponent(request.getComponent());
        String plan = normalisePlan(request.getPlan());

        if (ruleManager.getRule(plan, component) == null) {
            throw new RuleNotFoundException(
                    "No rule exists for component '" + component + "' and plan '"
                            + plan + "'. Use POST /api/admin/rules to create it.");
        }

        return apply(request, component, plan);
    }

    /**
     * Creates the rule if absent, replaces it otherwise. Used when seeding the
     * registry from configuration, where neither outcome is an error.
     */
    public RuleResponse createOrReplace(RuleRequest request) {

        String component = normaliseComponent(request.getComponent());
        String plan = normalisePlan(request.getPlan());

        return apply(request, component, plan);
    }

    public List<RuleResponse> findAll() {

        List<RuleResponse> responses = new ArrayList<>();

        ruleManager.getAllRules().forEach((plan, byComponent) ->
                byComponent.forEach((component, rule) ->
                        responses.add(toResponse(plan, component, rule))));

        return responses;
    }

    /**
     * @throws RuleNotFoundException if the component has no rule on any plan
     */
    public List<RuleResponse> findByComponent(String component) {

        String normalised = normaliseComponent(component);
        Map<String, Rule> byPlan = ruleManager.getRulesForComponent(normalised);

        if (byPlan.isEmpty()) {
            throw new RuleNotFoundException(
                    "No rules configured for component '" + normalised + "'");
        }

        List<RuleResponse> responses = new ArrayList<>();

        byPlan.forEach((plan, rule) ->
                responses.add(toResponse(plan, normalised, rule)));

        return responses;
    }

    /**
     * Removes every rule of a component together with its path mapping.
     *
     * @return the number of rules removed
     * @throws RuleNotFoundException if the component is unknown
     */
    public int deleteByComponent(String component, boolean resetState) {

        String normalised = normaliseComponent(component);

        if (ruleManager.getRulesForComponent(normalised).isEmpty()
                && ruleManager.getPathForComponent(normalised) == null) {
            throw new RuleNotFoundException(
                    "No rules configured for component '" + normalised + "'");
        }

        int removed = ruleManager.removeComponent(normalised);

        log.info("Removed {} rule(s) for component {}", removed, normalised);

        if (resetState) {
            long keys = resetStateForComponent(normalised);
            log.info("Reset {} state key(s) for component {}", keys, normalised);
        }

        return removed;
    }

    /** Replaces the whole registry with the supplied rules. */
    public void replaceAll(List<RuleRequest> requests) {
        ruleManager.clear();
        requests.forEach(this::createOrReplace);
    }

    /**
     * Drops every Redis state key belonging to a component, across all users.
     * Callers use this after a rule change when they want a clean slate.
     */
    public long resetStateForComponent(String component) {
        return stateManager.resetAllStateForComponent(normaliseComponent(component));
    }

    // ------------------------------------------------------------------

    private RuleResponse apply(RuleRequest request, String component, String plan) {

        String method = request.getHttpMethod().toUpperCase();
        String apiPath = request.getApiPath();

        validatePathOwnership(component, method, apiPath);

        Path path = new Path();
        path.setComponent(component);
        path.setHttpMethod(method);
        path.setUrlPattern(apiPath);

        Rule rule = new Rule();
        rule.setComponent(component);
        rule.setLimitCount(request.getLimit());
        rule.setWindowInSeconds(request.getWindowInSeconds());
        rule.setAlgorithmType(request.getAlgorithm());

        // Path first: a request that resolves the component must always find a
        // rule behind it.
        ruleManager.mapPathToComponent(path);
        ruleManager.addRule(plan, rule);

        log.info("Configured rule {} {} -> component={} plan={} limit={} window={}s algorithm={}",
                method, apiPath, component, plan, rule.getLimitCount(),
                rule.getWindowInSeconds(), rule.getAlgorithmType());

        return toResponse(plan, component, rule);
    }

    /**
     * A component owns exactly one (method, path) and a (method, path) is owned
     * by exactly one component. Anything else would make rule resolution
     * ambiguous.
     */
    private void validatePathOwnership(String component, String method, String apiPath) {

        String owner = ruleManager.getComponent(method, apiPath);

        if (owner != null && !owner.equals(component)) {
            throw new RuleConflictException(
                    method + " " + apiPath + " is already mapped to component '"
                            + owner + "'");
        }

        Path existing = ruleManager.getPathForComponent(component);

        if (existing != null
                && (!existing.getHttpMethod().equalsIgnoreCase(method)
                || !existing.getUrlPattern().equals(apiPath))) {

            throw new RuleConflictException(
                    "Component '" + component + "' is already mapped to "
                            + existing.getHttpMethod() + " " + existing.getUrlPattern()
                            + ". Delete the component before remapping it.");
        }
    }

    private RuleResponse toResponse(String plan, String component, Rule rule) {

        Path path = ruleManager.getPathForComponent(component);

        return new RuleResponse(
                component,
                path == null ? null : path.getHttpMethod(),
                path == null ? null : path.getUrlPattern(),
                plan,
                rule.getLimitCount(),
                rule.getWindowInSeconds(),
                rule.getAlgorithmType());
    }

    private String normaliseComponent(String component) {
        if (component == null || component.isBlank()) {
            throw new InvalidRuleException("component must not be blank");
        }
        return component.trim().toUpperCase();
    }

    private String normalisePlan(String plan) {
        if (plan == null || plan.isBlank()) {
            throw new InvalidRuleException("plan must not be blank");
        }
        return plan.trim().toUpperCase();
    }
}