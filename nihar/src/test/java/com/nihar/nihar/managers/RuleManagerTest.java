
package com.nihar.nihar.managers;

import com.nihar.nihar.entity.Path;
import com.nihar.nihar.entity.Rule;
import com.nihar.nihar.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RuleManagerTest {

    private RuleManager ruleManager;

    @BeforeEach
    void setUp() {
        ruleManager = new RuleManager();
    }

    private Path createPath(String method, String url, String component) {
        Path path = new Path();
        path.setHttpMethod(method);
        path.setUrlPattern(url);
        path.setComponent(component);
        return path;
    }

    private Rule createRule(String component, int limit) {
        Rule rule = new Rule();
        rule.setComponent(component);
        rule.setLimitCount(limit);
        rule.setWindowInSeconds(60);
        return rule;
    }

    @Test
    void shouldMapPathToComponent() {
        ruleManager.mapPathToComponent(
                createPath("POST", "/api/run", "CODE_RUN")
        );

        assertEquals(
                "CODE_RUN",
                ruleManager.getComponent("POST", "/api/run")
        );
    }

    @Test
    void shouldTreatHttpMethodCaseInsensitively() {
        ruleManager.mapPathToComponent(
                createPath("POST", "/api/run", "CODE_RUN")
        );

        assertEquals(
                "CODE_RUN",
                ruleManager.getComponent("post", "/api/run")
        );
    }

    @Test
    void shouldReturnNullForUnknownPath() {
        assertNull(
                ruleManager.getComponent("GET", "/unknown")
        );
    }

    @Test
    void shouldAddAndRetrieveRuleForPlan() {
        Rule rule = createRule("CODE_RUN", 10);
        ruleManager.addRule("FREE", rule);

        assertSame(rule, ruleManager.getRule("FREE", "CODE_RUN"));
    }

    @Test
    void shouldReturnNullForUnknownPlanOrComponent() {
        assertNull(ruleManager.getRule("UNKNOWN", "CODE_RUN"));

        ruleManager.addRule("FREE", createRule("CODE_RUN", 10));

        assertNull(ruleManager.getRule("FREE", "LOGIN"));
    }

    @Test
    void shouldResolveApplicableRuleForUsersPlan() {
        Path path = createPath("POST", "/api/run", "CODE_RUN");
        Rule rule = createRule("CODE_RUN", 10);

        ruleManager.mapPathToComponent(path);
        ruleManager.addRule("FREE", rule);

        User user = new User();
        user.setUserId("user-1");
        user.setActiveSubscriptionPlans(Set.of("FREE"));

        assertSame(rule, ruleManager.getApplicableRule(user, path));
    }

    @Test
    void shouldReturnNullWhenUserHasNoMatchingPlan() {
        Path path = createPath("POST", "/api/run", "CODE_RUN");

        ruleManager.mapPathToComponent(path);
        ruleManager.addRule("PREMIUM", createRule("CODE_RUN", 100));

        User user = new User();
        user.setActiveSubscriptionPlans(Set.of("FREE"));

        assertNull(ruleManager.getApplicableRule(user, path));
    }

    @Test
    void shouldReturnNullWhenPathIsNotMapped() {
        User user = new User();
        user.setActiveSubscriptionPlans(Set.of("FREE"));

        Path path = createPath("POST", "/unknown", "UNKNOWN");

        assertNull(ruleManager.getApplicableRule(user, path));
    }
}
