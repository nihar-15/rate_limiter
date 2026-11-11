
package com.nihar.nihar.tests.integration;

import com.nihar.nihar.algorithm.FixedWindow;
import com.nihar.nihar.entity.*;
import com.nihar.nihar.managers.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterRedisIntegrationTest {

    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static StringRedisTemplate redisTemplate;

    RuleManager ruleManager;
    UserManager userManager;
    RateLimitStateManager stateManager;

    @BeforeAll
    static void startRedis() {
        redis.start();

        connectionFactory = new LettuceConnectionFactory(
                redis.getHost(),
                redis.getMappedPort(6379)
        );

        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        redis.stop();
    }

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory()
                .getConnection()
                .serverCommands()
                .flushDb();

        ruleManager = new RuleManager();
        userManager = new UserManager(redisTemplate);

        stateManager = new RateLimitStateManager(
                redisTemplate,
                JsonMapper.builder().build()
        );
    }

    @Test
    void shouldPersistAndRetrieveRateLimitStateFromRedis() {
        UserStatus status = new UserStatus();
        status.setUserId("user-1");
        status.setComponent("CODE_RUN");
        status.setRequestCount(2);

        stateManager.updateState("user-1", "CODE_RUN", status);

        UserStatus retrieved = stateManager.getUserStatus(
                "user-1", "CODE_RUN"
        );

        assertEquals(2, retrieved.getRequestCount());
        assertEquals("user-1", retrieved.getUserId());
        assertEquals("CODE_RUN", retrieved.getComponent());
    }

    @Test
    void shouldRestrictAndThenUnrestrictUser() {
        User user = new User();
        user.setUserId("user-1");

        userManager.restrictUserTemporarily(
                user, "CODE_RUN", 10
        );

        assertTrue(userManager.isRestricted("user-1", "CODE_RUN"));

        userManager.removeRestriction("user-1", "CODE_RUN");

        assertFalse(userManager.isRestricted("user-1", "CODE_RUN"));
    }

    @Test
    void shouldEnforceFixedWindowLimitUsingRedisBackedState() {
        Path path = new Path();
        path.setHttpMethod("POST");
        path.setUrlPattern("/api/run");
        path.setComponent("CODE_RUN");

        ruleManager.mapPathToComponent(path);

        Rule rule = new Rule();
        rule.setComponent("CODE_RUN");
        rule.setLimitCount(2);
        rule.setWindowInSeconds(60);

        ruleManager.addRule("FREE", rule);

        User user = new User();
        user.setUserId("user-1");
        user.setActiveSubscriptionPlans(Set.of("FREE"));

        Request request = new Request();
        request.setPath(path);

        FixedWindow algorithm = new FixedWindow(
                ruleManager,
                userManager,
                stateManager
        );

        assertTrue(algorithm.isAllowed(user, request));
        assertTrue(algorithm.isAllowed(user, request));
        assertFalse(algorithm.isAllowed(user, request));

        UserStatus stored = stateManager.getUserStatus(
                "user-1", "CODE_RUN"
        );

        assertEquals(2, stored.getRequestCount());
    }

    @Test
    void shouldNotAllowBlockedUserEvenWhenRateLimitStateIsAvailable() {
        User user = new User();
        user.setUserId("user-2");
        user.setActiveSubscriptionPlans(Set.of("FREE"));

        Path path = new Path();
        path.setHttpMethod("POST");
        path.setUrlPattern("/api/run");
        path.setComponent("CODE_RUN");

        ruleManager.mapPathToComponent(path);

        Rule rule = new Rule();
        rule.setComponent("CODE_RUN");
        rule.setLimitCount(5);
        rule.setWindowInSeconds(60);

        ruleManager.addRule("FREE", rule);

        Request request = new Request();
        request.setPath(path);

        userManager.addToBlackList(user, "CODE_RUN", 10);

        FixedWindow algorithm = new FixedWindow(
                ruleManager,
                userManager,
                stateManager
        );

        assertFalse(algorithm.isAllowed(user, request));
    }
}
