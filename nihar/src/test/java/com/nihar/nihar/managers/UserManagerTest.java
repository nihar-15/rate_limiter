
package com.nihar.nihar.managers;

import com.nihar.nihar.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserManagerTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private UserManager userManager;
    private User user;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        userManager = new UserManager(redisTemplate);

        user = new User();
        user.setUserId("user-1");
    }

    @Test
    void shouldRestrictUserWithRequestedTtl() {
        userManager.restrictUserTemporarily(user, "CODE_RUN", 10);

        verify(valueOperations).set(
                "restricted:user-1:CODE_RUN",
                "user-1",
                Duration.ofMinutes(10)
        );
    }

    @Test
    void shouldBlockUserWithRequestedTtl() {
        userManager.addToBlackList(user, "CODE_RUN", 30);

        verify(valueOperations).set(
                "blocked:user-1:CODE_RUN",
                "user-1",
                Duration.ofMinutes(30)
        );
    }

    @Test
    void shouldReturnTrueWhenUserIsRestricted() {
        when(redisTemplate.hasKey("restricted:user-1:CODE_RUN"))
                .thenReturn(true);

        assertTrue(userManager.isRestricted("user-1", "CODE_RUN"));
    }

    @Test
    void shouldReturnFalseWhenUserIsNotRestricted() {
        when(redisTemplate.hasKey("restricted:user-1:CODE_RUN"))
                .thenReturn(false);

        assertFalse(userManager.isRestricted("user-1", "CODE_RUN"));
    }

    @Test
    void shouldReturnFalseWhenRedisReturnsNullForRestriction() {
        when(redisTemplate.hasKey("restricted:user-1:CODE_RUN"))
                .thenReturn(null);

        assertFalse(userManager.isRestricted("user-1", "CODE_RUN"));
    }

    @Test
    void shouldCheckBlacklistKey() {
        when(redisTemplate.hasKey("blocked:user-1:CODE_RUN"))
                .thenReturn(true);

        assertTrue(userManager.isBlocked("user-1", "CODE_RUN"));
    }

    @Test
    void shouldRemoveRestriction() {
        userManager.removeRestriction("user-1", "CODE_RUN");

        verify(redisTemplate).delete("restricted:user-1:CODE_RUN");
    }

    @Test
    void shouldRemoveUserFromBlacklist() {
        userManager.removeFromBlacklist("user-1", "CODE_RUN");

        verify(redisTemplate).delete("blocked:user-1:CODE_RUN");
    }
}
