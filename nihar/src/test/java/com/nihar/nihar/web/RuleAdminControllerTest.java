package com.nihar.nihar.web;

import com.nihar.nihar.config.SecurityConfig;
import com.nihar.nihar.enums.RateLimitType;
import com.nihar.nihar.exception.RuleConflictException;
import com.nihar.nihar.exception.RuleNotFoundException;
import com.nihar.nihar.service.RuleService;
import com.nihar.nihar.web.controller.RuleAdminController;
import com.nihar.nihar.web.dto.RuleResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RuleAdminController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("local")
class RuleAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RuleService ruleService;

    private static final String VALID_RULE = """
            {
              "component": "CODE_RUN",
              "httpMethod": "POST",
              "apiPath": "/api/demo/code-run",
              "plan": "FREE",
              "limit": 5,
              "windowInSeconds": 60,
              "algorithm": "FIXED_WINDOW"
            }
            """;

    private RuleResponse sampleResponse() {
        return new RuleResponse("CODE_RUN", "POST", "/api/demo/code-run",
                "FREE", 5, 60, RateLimitType.FIXED_WINDOW);
    }

    @Test
    void shouldCreateRuleAndReturn201() throws Exception {
        when(ruleService.create(any())).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/admin/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_RULE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.component").value("CODE_RUN"))
                .andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.limit").value(5))
                .andExpect(jsonPath("$.windowInSeconds").value(60))
                .andExpect(jsonPath("$.algorithm").value("FIXED_WINDOW"));

        verify(ruleService, never()).resetStateForComponent(anyString());
    }

    @Test
    void shouldResetStateWhenRequestedOnCreate() throws Exception {
        when(ruleService.create(any())).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/admin/rules?resetState=true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_RULE))
                .andExpect(status().isCreated());

        verify(ruleService).resetStateForComponent("CODE_RUN");
    }

    @Test
    void shouldReject400WhenLimitIsNotPositive() throws Exception {
        mockMvc.perform(post("/api/admin/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_RULE.replace("\"limit\": 5", "\"limit\": 0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0]").value("limit: limit must be at least 1"));

        verifyNoInteractions(ruleService);
    }

    @Test
    void shouldReject400WhenMandatoryFieldsAreMissing() throws Exception {
        mockMvc.perform(post("/api/admin/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                // one per mandatory field: component, httpMethod, apiPath, plan,
                // limit, windowInSeconds, algorithm
                .andExpect(jsonPath("$.details.length()").value(7));
    }

    @Test
    void shouldReject400ForAnUnknownAlgorithm() throws Exception {
        mockMvc.perform(post("/api/admin/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_RULE.replace("FIXED_WINDOW", "MAGIC_WINDOW")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
    }

    @Test
    void shouldReject400ForAPathThatDoesNotStartWithSlash() throws Exception {
        mockMvc.perform(post("/api/admin/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_RULE.replace("\"/api/demo/code-run\"", "\"api/demo/code-run\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("apiPath: apiPath must start with '/'"));
    }

    @Test
    void shouldReturn409WhenTheRuleAlreadyExists() throws Exception {
        when(ruleService.create(any()))
                .thenThrow(new RuleConflictException("A rule already exists"));

        mockMvc.perform(post("/api/admin/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_RULE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("RULE_CONFLICT"))
                .andExpect(jsonPath("$.message").value("A rule already exists"));
    }

    @Test
    void shouldListAllRules() throws Exception {
        when(ruleService.findAll()).thenReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/admin/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].apiPath").value("/api/demo/code-run"));
    }

    @Test
    void shouldListRulesOfOneComponent() throws Exception {
        when(ruleService.findByComponent("CODE_RUN")).thenReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/admin/rules/CODE_RUN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].component").value("CODE_RUN"));
    }

    @Test
    void shouldReturn404ForAnUnknownComponent() throws Exception {
        when(ruleService.findByComponent("GHOST"))
                .thenThrow(new RuleNotFoundException("No rules configured for component 'GHOST'"));

        mockMvc.perform(get("/api/admin/rules/GHOST"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RULE_NOT_FOUND"));
    }

    @Test
    void shouldUpdateRule() throws Exception {
        when(ruleService.update(any())).thenReturn(sampleResponse());

        mockMvc.perform(put("/api/admin/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_RULE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.component").value("CODE_RUN"));
    }

    @Test
    void shouldReturn404WhenUpdatingAMissingRule() throws Exception {
        when(ruleService.update(any()))
                .thenThrow(new RuleNotFoundException("No rule exists"));

        mockMvc.perform(put("/api/admin/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_RULE))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldDeleteComponent() throws Exception {
        when(ruleService.deleteByComponent(anyString(), anyBoolean())).thenReturn(2);

        mockMvc.perform(delete("/api/admin/rules/code_run?resetState=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.component").value("CODE_RUN"))
                .andExpect(jsonPath("$.rulesRemoved").value(2))
                .andExpect(jsonPath("$.stateReset").value(true));

        verify(ruleService).deleteByComponent("code_run", true);
    }
}
