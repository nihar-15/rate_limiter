package com.nihar.nihar.web;

import com.nihar.nihar.config.SecurityConfig;
import com.nihar.nihar.exception.AdminApiDisabledException;
import com.nihar.nihar.service.StateInspectionService;
import com.nihar.nihar.web.controller.StateAdminController;
import com.nihar.nihar.web.dto.StateResetResponse;
import com.nihar.nihar.web.dto.StateResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StateAdminController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("local")
class StateAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StateInspectionService stateInspectionService;

    @Test
    void shouldReturnTheStoredState() throws Exception {
        when(stateInspectionService.getState("user-101", "CODE_RUN")).thenReturn(
                new StateResponse("user-101", "CODE_RUN", true,
                        "ratelimit:user-101:CODE_RUN", 57,
                        3, 1_763_500_000_000L, List.of(1_763_500_000_001L),
                        2.5, 1_763_500_000_002L));

        mockMvc.perform(get("/api/admin/state/user-101/CODE_RUN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user-101"))
                .andExpect(jsonPath("$.component").value("CODE_RUN"))
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.redisKey").value("ratelimit:user-101:CODE_RUN"))
                .andExpect(jsonPath("$.ttlSeconds").value(57))
                .andExpect(jsonPath("$.requestCount").value(3))
                .andExpect(jsonPath("$.requestTimestamps.length()").value(1))
                .andExpect(jsonPath("$.availableTokens").value(2.5));
    }

    @Test
    void shouldReturn403WhenStateInspectionIsDisabled() throws Exception {
        when(stateInspectionService.getState(anyString(), anyString()))
                .thenThrow(new AdminApiDisabledException("State inspection APIs are disabled"));

        mockMvc.perform(get("/api/admin/state/user-101/CODE_RUN"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ADMIN_API_DISABLED"));
    }

    @Test
    void shouldResetStateForOneUserAndComponent() throws Exception {
        when(stateInspectionService.resetState("user-101", "CODE_RUN"))
                .thenReturn(new StateResetResponse("user-101", "CODE_RUN", 1, "State reset"));

        mockMvc.perform(delete("/api/admin/state/user-101/CODE_RUN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keysRemoved").value(1))
                .andExpect(jsonPath("$.message").value("State reset"));
    }

    @Test
    void shouldResetAllState() throws Exception {
        when(stateInspectionService.resetAll())
                .thenReturn(new StateResetResponse(null, null, 7, "All rate limit state reset"));

        mockMvc.perform(delete("/api/admin/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keysRemoved").value(7));
    }

    @Test
    void shouldReturn403WhenResetAllIsDisabled() throws Exception {
        when(stateInspectionService.resetAll())
                .thenThrow(new AdminApiDisabledException("Resetting all playground state is disabled"));

        mockMvc.perform(delete("/api/admin/state"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ADMIN_API_DISABLED"));
    }
}
