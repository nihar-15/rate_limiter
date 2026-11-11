
package com.nihar.nihar.managers;

import com.nihar.nihar.entity.*;
import java.util.*;
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

@Component
public class RuleManager {

    // Maps HTTP path + method to component
    private final Map<String, String> pathComponentMap =
            new HashMap<>();

    // Maps subscription plan -> component -> Rule
    private final Map<String, Map<String, Rule>> planRuleMap =
            new HashMap<>();

    // 1. Add a new rule for a subscription plan
    public void addRule(String plan, Rule rule) {

        planRuleMap
            .computeIfAbsent(plan, k -> new HashMap<>())
            .put(rule.getComponent(), rule);
    }

    // 2. Map an API path to a component
    public void mapPathToComponent(Path path) {

        String key = getPathKey(
            path.getHttpMethod(),
            path.getUrlPattern()
        );

        pathComponentMap.put(key, path.getComponent());
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

    private String getPathKey(String method, String url) {
        return method.toUpperCase() + ":" + url;
    }
}
