package com.nihar.nihar.managers;

import com.nihar.nihar.entity.UserStatus;
import com.nihar.nihar.enums.*;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class RateLimitStateManager {

    /** Every rate limit state key lives under this prefix. */
    public static final String KEY_PREFIX = "ratelimit:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RateLimitStateManager(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {

        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    private String getKey(String userId, String component) {
        return KEY_PREFIX + userId + ":" + component;
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

    // ------------------------------------------------------------------
    // Atomic execution
    // ------------------------------------------------------------------

    /**
     * Runs an algorithm's Lua script against the state key of this
     * user/component pair.
     *
     * <p>The whole read-decide-write cycle executes inside Redis, which is what
     * makes concurrent requests for the same user safe: Redis runs one script
     * at a time, so no two requests can observe the same pre-decrement state.
     * The script is expected to return three integers:
     * {@code allowed, remaining, retryAfterMillis}.</p>
     */
    public List<Long> execute(
            RedisScript<List> script,
            String userId,
            String component,
            Object... args) {

        // ARGV layout shared by every algorithm script:
        // <algorithm specific args...>, userId, component
        String[] arguments = new String[args.length + 2];
        for (int i = 0; i < args.length; i++) {
            arguments[i] = String.valueOf(args[i]);
        }
        arguments[args.length] = userId;
        arguments[args.length + 1] = component;

        @SuppressWarnings("unchecked")
        List<Long> result = redisTemplate.execute(
                script,
                Collections.singletonList(getKey(userId, component)),
                (Object[]) arguments
        );

        if (result == null || result.size() < 3) {
            throw new IllegalStateException(
                    "Rate limit script returned an unexpected result: " + result);
        }

        return result;
    }

    // ------------------------------------------------------------------
    // Inspection and reset
    // ------------------------------------------------------------------

    /** {@code true} when Redis currently holds state for this user/component. */
    public boolean hasState(String userId, String component) {
        return Boolean.TRUE.equals(
                redisTemplate.hasKey(getKey(userId, component)));
    }

    /**
     * Remaining time to live of the state key in seconds, or {@code -1} when the
     * key has no expiry and {@code -2} when it does not exist.
     */
    public long getTimeToLiveSeconds(String userId, String component) {
        Long ttl = redisTemplate.getExpire(
                getKey(userId, component), TimeUnit.SECONDS);
        return ttl == null ? -2L : ttl;
    }

    /** Drops the state of a single user/component. Returns true if a key was removed. */
    public boolean resetState(String userId, String component) {
        return Boolean.TRUE.equals(
                redisTemplate.delete(getKey(userId, component)));
    }

    /**
     * Drops every rate limit state key. Intended for the local playground only;
     * callers are responsible for gating this behind the admin switch.
     */
    public long resetAllState() {
        return deleteMatching(KEY_PREFIX + "*");
    }

    /** Drops the state of one component for every user. */
    public long resetAllStateForComponent(String component) {
        return deleteMatching(KEY_PREFIX + "*:" + component);
    }

    private long deleteMatching(String pattern) {

        Set<String> keys = redisTemplate.keys(pattern);

        if (keys == null || keys.isEmpty()) {
            return 0L;
        }

        Long removed = redisTemplate.delete(keys);
        return removed == null ? 0L : removed;
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