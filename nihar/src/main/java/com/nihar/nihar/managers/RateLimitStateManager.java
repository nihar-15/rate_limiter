package com.nihar.nihar.managers;

import com.nihar.nihar.entity.UserStatus;
import com.nihar.nihar.enums.*;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;

@Component
public class RateLimitStateManager {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RateLimitStateManager(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {

        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    private String getKey(String userId, String component) {
        return "ratelimit:" + userId + ":" + component;
    }

    public UserStatus getUserStatus(
            String userId, String component) {

        String key = getKey(userId, component);

        String data = redisTemplate.opsForValue().get(key);

        if (data == null) {
            UserStatus status = new UserStatus();
            status.setUserId(userId);
            status.setComponent(component);
            return status;
        }

        return deserialize(data);
    }

    public void updateState(
            String userId,
            String component,
            UserStatus status) {

        String key = getKey(userId, component);

        redisTemplate.opsForValue().set(
                key,
                serialize(status)
        );
    }

    private String serialize(UserStatus status) {
        try {
            return objectMapper.writeValueAsString(status);
        } catch (JacksonException e) {
            throw new RuntimeException("Serialization failed", e);
        }
    }

    private UserStatus deserialize(String data) {
        try {
            return objectMapper.readValue(data, UserStatus.class);
        } catch (JacksonException e) {
            throw new RuntimeException("Deserialization failed", e);
        }
    }
}