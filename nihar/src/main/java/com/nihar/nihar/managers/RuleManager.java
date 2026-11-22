
package com.nihar.nihar.managers;

import com.nihar.nihar.entity.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;


/*
Map 1 : Lets map path to componet
POST:/api/code/run     → CODE_RUN
POST:/api/code/submit  → CODE_SUBMIT
POST:/api/login        → LOGIN

Map 2 : Mapping Plans + Component --> Rule
FREE + CODE_RUN       → Rule(10, 60, FIXED_WINDOW)
FREE + CODE_SUBMIT    → Rule(5, 60, FIXED_WINDOW)

PREMIUM + CODE_RUN    → Rule(100, 60, TOKEN_BUCKET)
PREMIUM + CODE_SUBMIT → Rule(50, 60, SLIDING_WINDOW)


*/

/**
 * In-memory registry of path mappings and per-plan rules.
 *
 * <p>Rules are mutated at runtime by the admin APIs while request threads read
 * them, so all three maps are concurrent. Replacing a rule is a single
 * {@code put}, which means a request either sees the old rule or the new one -
 * never a half-updated one.</p>
 */
@Component
public class RuleManager {

    // Maps HTTP path + method to component
    private final Map<String, String> pathComponentMap =
            new ConcurrentHashMap<>();

    // Maps subscription plan -> component -> Rule
    private final Map<String, Map<String, Rule>> planRuleMap =
            new ConcurrentHashMap<>();

    // Reverse view of map 1: component -> the path that serves it
    private final Map<String, Path> componentPathMap =
            new ConcurrentHashMap<>();

    // 1. Add a new rule for a subscription plan
    public void addRule(String plan, Rule rule) {

        planRuleMap
            .computeIfAbsent(plan, k -> new ConcurrentHashMap<>())
            .put(rule.getComponent(), rule);
    }

    // 2. Map an API path to a component
    public void mapPathToComponent(Path path) {

        String key = getPathKey(
            path.getHttpMethod(),
            path.getUrlPattern()
        );

        pathComponentMap.put(key, path.getComponent());
        componentPathMap.put(path.getComponent(), path);
    }

    // 3. Get component corresponding to an API path
    public String getComponent(String method, String url) {

        String key = getPathKey(method, url);

        return pathComponentMap.get(key);
    }

    // 4. Get rule for a plan and component
    public Rule getRule(String plan, String component) {

        Map<String, Rule> componentRules =
                planRuleMap.get(plan);

        if (componentRules == null) {
            return null;
        }

        return componentRules.get(component);
    }

    // 5. Resolve the applicable rule
    public Rule getApplicableRule(User user, Path path) {

        String component = getComponent(
            path.getHttpMethod(),
            path.getUrlPattern()
        );

        if (component == null) {
            return null;
        }

        for (String plan : user.getActiveSubscriptionPlans()) {

            Rule rule = getRule(plan, component);

            if (rule != null) {
                return rule;
            }
        }

        return null;
    }

    // ------------------------------------------------------------------
    // Runtime administration support
    // ------------------------------------------------------------------

    /** The path registered for a component, or {@code null} when unmapped. */
    public Path getPathForComponent(String component) {
        return componentPathMap.get(component);
    }

    /** Every component that currently has at least a path mapping. */
    public Set<String> getComponents() {
        Set<String> components = new TreeSet<>(componentPathMap.keySet());

        planRuleMap.values().forEach(byComponent ->
                components.addAll(byComponent.keySet()));

        return components;
    }

    /** Subscription plans that currently hold at least one rule. */
    public Set<String> getPlans() {
        return new TreeSet<>(planRuleMap.keySet());
    }

    /** All rules for one component, keyed by subscription plan. */
    public Map<String, Rule> getRulesForComponent(String component) {

        Map<String, Rule> byPlan = new TreeMap<>();

        planRuleMap.forEach((plan, componentRules) -> {
            Rule rule = componentRules.get(component);
            if (rule != null) {
                byPlan.put(plan, rule);
            }
        });

        return byPlan;
    }

    /** Every configured rule, as plan -> component -> rule. */
    public Map<String, Map<String, Rule>> getAllRules() {

        Map<String, Map<String, Rule>> snapshot = new TreeMap<>();

        planRuleMap.forEach((plan, componentRules) ->
                snapshot.put(plan, new TreeMap<>(componentRules)));

        return snapshot;
    }

    /** Removes one plan's rule for a component. Returns true if it existed. */
    public boolean removeRule(String plan, String component) {

        Map<String, Rule> componentRules = planRuleMap.get(plan);

        if (componentRules == null) {
            return false;
        }

        boolean removed = componentRules.remove(component) != null;

        if (componentRules.isEmpty()) {
            planRuleMap.remove(plan, componentRules);
        }

        return removed;
    }

    /**
     * Removes a component entirely: its rules across every plan and its path
     * mapping. Returns the number of rules that were removed.
     */
    public int removeComponent(String component) {

        int removed = 0;

        for (String plan : new ArrayList<>(planRuleMap.keySet())) {
            if (removeRule(plan, component)) {
                removed++;
            }
        }

        Path path = componentPathMap.remove(component);

        if (path != null) {
            pathComponentMap.remove(
                    getPathKey(path.getHttpMethod(), path.getUrlPattern()));
        }

        return removed;
    }

    /** Wipes the whole registry. Used when reloading configuration. */
    public void clear() {
        pathComponentMap.clear();
        planRuleMap.clear();
        componentPathMap.clear();
    }

    private String getPathKey(String method, String url) {
        return method.toUpperCase() + ":" + url;
    }
}