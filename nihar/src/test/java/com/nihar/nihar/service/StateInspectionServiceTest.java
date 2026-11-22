package com.nihar.nihar.service;

import com.nihar.nihar.config.RateLimiterProperties;
import com.nihar.nihar.entity.UserStatus;
import com.nihar.nihar.exception.AdminApiDisabledException;
import com.nihar.nihar.managers.RateLimitStateManager;
import com.nihar.nihar.web.dto.StateResetResponse;
import com.nihar.nihar.web.dto.StateResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class StateInspectionServiceTest {

    private RateLimitStateManager stateManager;
    private RateLimiterProperties properties;
    private StateInspectionService service;

    @BeforeEach
    void setUp() {
        stateManager = mock(RateLimitStateManager.class);
        properties = new RateLimiterProperties();
        service = new StateInspectionService(stateManager, properties);
    }

    private void enableInspection() {
        properties.getAdmin().setStateInspectionEnabled(true);
    }

    @Test
    void shouldRefuseInspectionByDefault() {
        AdminApiDisabledException ex = assertThrows(
                AdminApiDisabledException.class,
                () -> service.getState("user-1", "CODE_RUN"));

        assertTrue(ex.getMessage().contains("state-inspection-enabled"));
        verifyNoInteractions(stateManager);
    }

    @Test
    void shouldRefuseResetByDefault() {
        assertThrows(
                AdminApiDisabledException.class,
                () -> service.resetState("user-1", "CODE_RUN"));
    }

    @Test
    void shouldRefuseResetAllByDefault() {
        AdminApiDisabledException ex = assertThrows(
                AdminApiDisabledException.class,
                () -> service.resetAll());

        assertTrue(ex.getMessage().contains("allow-reset-all"));
        verifyNoInteractions(stateManager);
    }

    @Test
    void shouldExposeEveryAlgorithmFieldWhenEnabled() {
        enableInspection();

        UserStatus status = new UserStatus();
        status.setUserId("user-1");
        status.setComponent("CODE_RUN");
        status.setRequestCount(3);
        status.setWindowStartTimestamp(1_000L);
        status.getRequestTimestamps().add(1_200L);
        status.setAvailableTokens(2.5);
        status.setLastRefillTimestamp(1_300L);

        when(stateManager.hasState("user-1", "CODE_RUN")).thenReturn(true);
        when(stateManager.getUserStatus("user-1", "CODE_RUN")).thenReturn(status);
        when(stateManager.getTimeToLiveSeconds("user-1", "CODE_RUN")).thenReturn(42L);

        StateResponse response = service.getState("user-1", "CODE_RUN");

        assertTrue(response.exists());
        assertEquals("ratelimit:user-1:CODE_RUN", response.redisKey());
        assertEquals(42L, response.ttlSeconds());
        assertEquals(3, response.requestCount());
        assertEquals(1_000L, response.windowStartTimestamp());
        assertEquals(1, response.requestTimestamps().size());
        assertEquals(2.5, response.availableTokens(), 0.0001);
        assertEquals(1_300L, response.lastRefillTimestamp());
    }

    @Test
    void shouldUpperCaseTheComponentBeforeLookup() {
        enableInspection();
        when(stateManager.getUserStatus(anyString(), anyString()))
                .thenReturn(new UserStatus());

        service.getState("user-1", " code_run ");

        verify(stateManager).getUserStatus("user-1", "CODE_RUN");
    }

    @Test
    void shouldReportWhenNothingWasStored() {
        enableInspection();
        when(stateManager.resetState("user-1", "CODE_RUN")).thenReturn(false);

        StateResetResponse response = service.resetState("user-1", "CODE_RUN");

        assertEquals(0, response.keysRemoved());
        assertTrue(response.message().contains("No state"));
    }

    @Test
    void shouldResetWhenEnabled() {
        enableInspection();
        when(stateManager.resetState("user-1", "CODE_RUN")).thenReturn(true);

        StateResetResponse response = service.resetState("user-1", "CODE_RUN");

        assertEquals(1, response.keysRemoved());
        assertEquals("State reset", response.message());
    }

    @Test
    void shouldResetEverythingWhenExplicitlyAllowed() {
        properties.getAdmin().setAllowResetAll(true);
        when(stateManager.resetAllState()).thenReturn(9L);

        StateResetResponse response = service.resetAll();

        assertEquals(9, response.keysRemoved());
        assertNull(response.userId());
        assertNull(response.component());
    }
}