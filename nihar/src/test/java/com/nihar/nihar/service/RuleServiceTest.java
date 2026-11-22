package com.nihar.nihar.service;

import com.nihar.nihar.enums.RateLimitType;
import com.nihar.nihar.exception.InvalidRuleException;
import com.nihar.nihar.exception.RuleConflictException;
import com.nihar.nihar.exception.RuleNotFoundException;
import com.nihar.nihar.managers.RateLimitStateManager;
import com.nihar.nihar.managers.RuleManager;
import com.nihar.nihar.web.dto.RuleRequest;
import com.nihar.nihar.web.dto.RuleResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RuleServiceTest {

    private RuleManager ruleManager;
    private RateLimitStateManager stateManager;
    private RuleService ruleService;

    @BeforeEach
    void setUp() {
        ruleManager = new RuleManager();
        stateManager = mock(RateLimitStateManager.class);
        ruleService = new RuleService(ruleManager, stateManager);
    }

    private RuleRequest request(
            String component, String method, String path,
            String plan, int limit, int window, RateLimitType algorithm) {

        RuleRequest request = new RuleRequest();
        request.setComponent(component);
        request.setHttpMethod(method);
        request.setApiPath(path);
        request.setPlan(plan);
        request.setLimit(limit);
        request.setWindowInSeconds(window);
        request.setAlgorithm(algorithm);
        return request;
    }

    private RuleRequest freeCodeRun() {
        return request("CODE_RUN", "POST", "/api/demo/code-run",
                "FREE", 5, 60, RateLimitType.FIXED_WINDOW);
    }

    @Test
    void shouldCreateRuleAndRegisterItsPath() {
        RuleResponse response = ruleService.create(freeCodeRun());

        assertEquals("CODE_RUN", response.component());
        assertEquals("POST", response.httpMethod());
        assertEquals("/api/demo/code-run", response.apiPath());
        assertEquals("FREE", response.plan());
        assertEquals(5, response.limit());
        assertEquals(60, response.windowInSeconds());
        assertEquals(RateLimitType.FIXED_WINDOW, response.algorithm());

        assertEquals("CODE_RUN",
                ruleManager.getComponent("POST", "/api/demo/code-run"));
        assertNotNull(ruleManager.getRule("FREE", "CODE_RUN"));
    }

    @Test
    void shouldNormaliseComponentAndPlanToUpperCase() {
        RuleResponse response = ruleService.create(
                request("code_run", "post", "/api/demo/code-run",
                        "free", 5, 60, RateLimitType.FIXED_WINDOW));

        assertEquals("CODE_RUN", response.component());
        assertEquals("FREE", response.plan());
        assertEquals("POST", response.httpMethod());
        assertNotNull(ruleManager.getRule("FREE", "CODE_RUN"));
    }

    @Test
    void shouldRejectDuplicateRuleForSameComponentAndPlan() {
        ruleService.create(freeCodeRun());

        RuleConflictException ex = assertThrows(
                RuleConflictException.class,
                () -> ruleService.create(freeCodeRun()));

        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    void shouldAllowSameComponentOnDifferentPlans() {
        ruleService.create(freeCodeRun());

        RuleResponse premium = ruleService.create(
                request("CODE_RUN", "POST", "/api/demo/code-run",
                        "PREMIUM", 20, 60, RateLimitType.TOKEN_BUCKET));

        assertEquals("PREMIUM", premium.plan());
        assertEquals(2, ruleService.findAll().size());
        assertEquals(2, ruleService.findByComponent("CODE_RUN").size());
    }

    @Test
    void shouldRejectPathAlreadyOwnedByAnotherComponent() {
        ruleService.create(freeCodeRun());

        RuleConflictException ex = assertThrows(
                RuleConflictException.class,
                () -> ruleService.create(request(
                        "OTHER", "POST", "/api/demo/code-run",
                        "FREE", 5, 60, RateLimitType.FIXED_WINDOW)));

        assertTrue(ex.getMessage().contains("already mapped to component 'CODE_RUN'"));
    }

    @Test
    void shouldRejectRemappingAComponentToADifferentPath() {
        ruleService.create(freeCodeRun());

        assertThrows(
                RuleConflictException.class,
                () -> ruleService.create(request(
                        "CODE_RUN", "POST", "/api/demo/somewhere-else",
                        "PREMIUM", 5, 60, RateLimitType.FIXED_WINDOW)));
    }

    @Test
    void shouldUpdateExistingRule() {
        ruleService.create(freeCodeRun());

        RuleResponse updated = ruleService.update(
                request("CODE_RUN", "POST", "/api/demo/code-run",
                        "FREE", 50, 10, RateLimitType.SLIDING_WINDOW));

        assertEquals(50, updated.limit());
        assertEquals(10, updated.windowInSeconds());
        assertEquals(RateLimitType.SLIDING_WINDOW, updated.algorithm());

        assertEquals(50, ruleManager.getRule("FREE", "CODE_RUN").getLimitCount());
        assertEquals(RateLimitType.SLIDING_WINDOW,
                ruleManager.getRule("FREE", "CODE_RUN").getAlgorithmType());
    }

    @Test
    void shouldRejectUpdateOfMissingRule() {
        RuleNotFoundException ex = assertThrows(
                RuleNotFoundException.class,
                () -> ruleService.update(freeCodeRun()));

        assertTrue(ex.getMessage().contains("No rule exists"));
    }

    @Test
    void createOrReplaceShouldNotComplainAboutExistingRules() {
        ruleService.createOrReplace(freeCodeRun());

        RuleResponse replaced = ruleService.createOrReplace(
                request("CODE_RUN", "POST", "/api/demo/code-run",
                        "FREE", 9, 60, RateLimitType.FIXED_WINDOW));

        assertEquals(9, replaced.limit());
        assertEquals(1, ruleService.findAll().size());
    }

    @Test
    void shouldListAllRulesAcrossPlans() {
        ruleService.create(freeCodeRun());
        ruleService.create(request("CODE_SUBMIT", "POST", "/api/demo/code-submit",
                "FREE", 3, 30, RateLimitType.SLIDING_WINDOW));

        List<RuleResponse> all = ruleService.findAll();

        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(r -> r.component().equals("CODE_RUN")));
        assertTrue(all.stream().anyMatch(r -> r.component().equals("CODE_SUBMIT")));
    }

    @Test
    void shouldReturnEmptyListWhenNothingIsConfigured() {
        assertTrue(ruleService.findAll().isEmpty());
    }

    @Test
    void shouldRejectLookupOfUnknownComponent() {
        assertThrows(
                RuleNotFoundException.class,
                () -> ruleService.findByComponent("NOPE"));
    }

    @Test
    void shouldDeleteComponentAcrossEveryPlan() {
        ruleService.create(freeCodeRun());
        ruleService.create(request("CODE_RUN", "POST", "/api/demo/code-run",
                "PREMIUM", 20, 60, RateLimitType.TOKEN_BUCKET));

        int removed = ruleService.deleteByComponent("CODE_RUN", false);

        assertEquals(2, removed);
        assertTrue(ruleService.findAll().isEmpty());
        assertNull(ruleManager.getComponent("POST", "/api/demo/code-run"));
        verify(stateManager, never()).resetAllStateForComponent(anyString());
    }

    @Test
    void shouldResetStateWhenDeletingWithResetRequested() {
        ruleService.create(freeCodeRun());
        when(stateManager.resetAllStateForComponent("CODE_RUN")).thenReturn(4L);

        ruleService.deleteByComponent("CODE_RUN", true);

        verify(stateManager).resetAllStateForComponent("CODE_RUN");
    }

    @Test
    void shouldRejectDeleteOfUnknownComponent() {
        assertThrows(
                RuleNotFoundException.class,
                () -> ruleService.deleteByComponent("GHOST", false));
    }

    @Test
    void shouldRejectBlankComponentAndPlan() {
        RuleRequest blankComponent = freeCodeRun();
        blankComponent.setComponent("   ");
        assertThrows(InvalidRuleException.class, () -> ruleService.create(blankComponent));

        RuleRequest blankPlan = freeCodeRun();
        blankPlan.setPlan(null);
        assertThrows(InvalidRuleException.class, () -> ruleService.create(blankPlan));
    }

    @Test
    void replaceAllShouldWipeAndReloadTheRegistry() {
        ruleService.create(freeCodeRun());

        ruleService.replaceAll(List.of(
                request("LOGIN", "POST", "/api/login",
                        "FREE", 3, 60, RateLimitType.SLIDING_WINDOW)));

        List<RuleResponse> all = ruleService.findAll();

        assertEquals(1, all.size());
        assertEquals("LOGIN", all.get(0).component());
        assertNull(ruleManager.getComponent("POST", "/api/demo/code-run"));
    }
}