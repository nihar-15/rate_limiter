
package com.nihar.nihar.managers;

import com.nihar.nihar.entity.User;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class UserManager {

    private final StringRedisTemplate redisTemplate;

    public UserManager(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    private String getKey(String status, String userId, String component) {
        return status + ":" + userId + ":" + component;
    }

    public void restrictUserTemporarily(
            User user, String component, long durationMinutes) {

        String key = getKey(
            "restricted", user.getUserId(), component
        );

        redisTemplate.opsForValue().set(
            key,
            user.getUserId(),
            Duration.ofMinutes(durationMinutes)
        );
    }

    public void addToBlackList(
            User user, String component, long durationMinutes) {

        String key = getKey(
            "blocked", user.getUserId(), component
        );

        redisTemplate.opsForValue().set(
            key,
            user.getUserId(),
            Duration.ofMinutes(durationMinutes)
        );
    }

    public boolean isRestricted(String userId, String component) {

        String key = getKey("restricted", userId, component);

        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public boolean isBlocked(String userId, String component) {

        String key = getKey("blocked", userId, component);

        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public void removeRestriction(String userId, String component) {
        redisTemplate.delete(
            getKey("restricted", userId, component)
        );
    }

    public void removeFromBlacklist(String userId, String component) {
        redisTemplate.delete(
            getKey("blocked", userId, component)
        );
    }
}
