
package com.nihar.nihar.managers;

import com.nihar.nihar.entity.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RateLimitStateManagerTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RateLimitStateManager stateManager;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        stateManager = new RateLimitStateManager(
                redisTemplate,
                JsonMapper.builder().build()
        );
    }

    @Test
    void shouldReturnNewStatusWhenRedisKeyDoesNotExist() {
        when(valueOperations.get("ratelimit:user-1:CODE_RUN"))
                .thenReturn(null);

        UserStatus status = stateManager.getUserStatus(
                "user-1", "CODE_RUN"
        );

        assertEquals("user-1", status.getUserId());
        assertEquals("CODE_RUN", status.getComponent());
        assertEquals(0, status.getRequestCount());
        assertNotNull(status.getRequestTimestamps());
    }

    @Test
    void shouldPersistStatusAsJson() {
        UserStatus status = new UserStatus();
        status.setUserId("user-1");
        status.setComponent("CODE_RUN");
        status.setRequestCount(3);
        status.setWindowStartTimestamp(123456L);

        stateManager.updateState("user-1", "CODE_RUN", status);

        verify(valueOperations).set(
                eq("ratelimit:user-1:CODE_RUN"),
                argThat(json ->
                        json.contains("\"userId\":\"user-1\"")
                        && json.contains("\"component\":\"CODE_RUN\"")
                        && json.contains("\"requestCount\":3")
                )
        );
    }

    @Test
    void shouldDeserializeExistingStatus() {
        String json = """
                {
                  "userId": "user-1",
                  "component": "CODE_RUN",
                  "requestCount": 4,
                  "windowStartTimestamp": 123456,
                  "requestTimestamps": [],
                  "availableTokens": 0.0,
                  "lastRefillTimestamp": 0
                }
                """;

        when(valueOperations.get("ratelimit:user-1:CODE_RUN"))
                .thenReturn(json);

        UserStatus status = stateManager.getUserStatus(
                "user-1", "CODE_RUN"
        );

        assertEquals("user-1", status.getUserId());
        assertEquals("CODE_RUN", status.getComponent());
        assertEquals(4, status.getRequestCount());
        assertEquals(123456L, status.getWindowStartTimestamp());
    }
}
