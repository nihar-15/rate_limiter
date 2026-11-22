package com.nihar.nihar.integration;

import com.nihar.nihar.algorithm.FixedWindow;
import com.nihar.nihar.algorithm.SlidingWindow;
import com.nihar.nihar.algorithm.TokenBucket;
import com.nihar.nihar.entity.Path;
import com.nihar.nihar.entity.Request;
import com.nihar.nihar.entity.Rule;
import com.nihar.nihar.entity.User;
import com.nihar.nihar.entity.UserStatus;
import com.nihar.nihar.enums.RateLimitType;
import com.nihar.nihar.interfaces.RateLimitingAlgorithms;
import com.nihar.nihar.managers.RateLimitStateManager;
import com.nihar.nihar.managers.RuleManager;
import com.nihar.nihar.managers.UserManager;
import com.nihar.nihar.model.RateLimitVerdict;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the atomic (Lua backed) decision path of each algorithm against a
 * real Redis.
 *
 * <p>The decisive tests here are the concurrency ones: they fire N simultaneous
 * requests for one user and assert that exactly {@code limit} of them are
 * allowed. The pre-existing read-modify-write path cannot pass those, which is
 * precisely why the Lua scripts exist.</p>
 */
class AtomicAlgorithmRedisIntegrationTest {

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
                redis.getHost(), redis.getMappedPort(6379));
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
                .getConnection().serverCommands().flushDb();

        ruleManager = new RuleManager();
        userManager = new UserManager(redisTemplate);
        stateManager = new RateLimitStateManager(
                redisTemplate, JsonMapper.builder().build());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Rule configure(String component, String url, String plan,
                           int limit, int window, RateLimitType type) {

        Path path = new Path();
        path.setComponent(component);
        path.setHttpMethod("POST");
        path.setUrlPattern(url);
        ruleManager.mapPathToComponent(path);

        Rule rule = new Rule();
        rule.setComponent(component);
        rule.setLimitCount(limit);
        rule.setWindowInSeconds(window);
        rule.setAlgorithmType(type);
        ruleManager.addRule(plan, rule);

        return rule;
    }

    private User user(String id, String plan) {
        User user = new User();
        user.setUserId(id);
        user.setActiveSubscriptionPlans(Set.of(plan));
        return user;
    }

    private Request requestFor(String url) {
        Path path = new Path();
        path.setHttpMethod("POST");
        path.setUrlPattern(url);

        Request request = new Request();
        request.setPath(path);
        return request;
    }

    private RateLimitingAlgorithms algorithmFor(RateLimitType type) {
        return switch (type) {
            case FIXED_WINDOW -> new FixedWindow(ruleManager, userManager, stateManager);
            case SLIDING_WINDOW -> new SlidingWindow(ruleManager, userManager, stateManager);
            case TOKEN_BUCKET -> new TokenBucket(ruleManager, userManager, stateManager);
        };
    }

    /** Fires {@code count} simultaneous evaluations and returns how many were allowed. */
    private long allowedConcurrently(
            RateLimitingAlgorithms algorithm, User user, Request request, int count)
            throws Exception {

        ExecutorService pool = Executors.newFixedThreadPool(count);
        CyclicBarrier barrier = new CyclicBarrier(count);

        try {
            List<Callable<Boolean>> tasks = IntStream.range(0, count)
                    .mapToObj(i -> (Callable<Boolean>) () -> {
                        barrier.await(20, TimeUnit.SECONDS);
                        return algorithm.evaluate(user, request).allowed();
                    })
                    .toList();

            List<Future<Boolean>> futures = pool.invokeAll(tasks, 60, TimeUnit.SECONDS);

            long allowed = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    allowed++;
                }
            }
            return allowed;
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // Fixed window
    // ------------------------------------------------------------------

    @Test
    void fixedWindowShouldAllowExactlyTheLimitAndThenReject() {
        configure("CODE_RUN", "/api/demo/code-run", "FREE", 3, 60,
                RateLimitType.FIXED_WINDOW);

        RateLimitingAlgorithms algorithm = algorithmFor(RateLimitType.FIXED_WINDOW);
        User user = user("user-1", "FREE");
        Request request = requestFor("/api/demo/code-run");

        assertEquals(2, algorithm.evaluate(user, request).remaining());
        assertEquals(1, algorithm.evaluate(user, request).remaining());

        RateLimitVerdict third = algorithm.evaluate(user, request);
        assertTrue(third.allowed());
        assertEquals(0, third.remaining());

        RateLimitVerdict fourth = algorithm.evaluate(user, request);
        assertFalse(fourth.allowed());
        assertEquals(0, fourth.remaining());
        assertTrue(fourth.retryAfterMillis() > 0, "a rejected request must say when to retry");
        assertTrue(fourth.retryAfterSeconds() <= 60);
    }

    @Test
    void fixedWindowShouldWriteStateReadableByTheExistingJavaDeserializer() {
        configure("CODE_RUN", "/api/demo/code-run", "FREE", 5, 60,
                RateLimitType.FIXED_WINDOW);

        RateLimitingAlgorithms algorithm = algorithmFor(RateLimitType.FIXED_WINDOW);
        User user = user("user-1", "FREE");
        Request request = requestFor("/api/demo/code-run");

        algorithm.evaluate(user, request);
        algorithm.evaluate(user, request);

        UserStatus status = stateManager.getUserStatus("user-1", "CODE_RUN");

        assertEquals("user-1", status.getUserId());
        assertEquals("CODE_RUN", status.getComponent());
        assertEquals(2, status.getRequestCount());
        assertTrue(status.getWindowStartTimestamp() > 0);
        assertNotNull(status.getRequestTimestamps());
        assertTrue(status.getRequestTimestamps().isEmpty());
    }

    @Test
    void fixedWindowShouldSetATtlSoAbandonedStateExpires() {
        configure("CODE_RUN", "/api/demo/code-run", "FREE", 5, 10,
                RateLimitType.FIXED_WINDOW);

        algorithmFor(RateLimitType.FIXED_WINDOW)
                .evaluate(user("user-1", "FREE"), requestFor("/api/demo/code-run"));

        long ttl = stateManager.getTimeToLiveSeconds("user-1", "CODE_RUN");

        assertTrue(ttl > 0 && ttl <= 21, "ttl should be about two windows, was " + ttl);
    }

    @Test
    void fixedWindowShouldStartAFreshWindowAfterItExpires() throws Exception {
        configure("CODE_RUN", "/api/demo/code-run", "FREE", 2, 1,
                RateLimitType.FIXED_WINDOW);

        RateLimitingAlgorithms algorithm = algorithmFor(RateLimitType.FIXED_WINDOW);
        User user = user("user-1", "FREE");
        Request request = requestFor("/api/demo/code-run");

        assertTrue(algorithm.evaluate(user, request).allowed());
        assertTrue(algorithm.evaluate(user, request).allowed());
        assertFalse(algorithm.evaluate(user, request).allowed());

        Thread.sleep(1_100);

        assertTrue(algorithm.evaluate(user, request).allowed(),
                "a new window must start once the previous one elapsed");
    }

    @Test
    void fixedWindowMustNotExceedTheLimitUnderConcurrency() throws Exception {
        configure("CODE_RUN", "/api/demo/code-run", "FREE", 5, 60,
                RateLimitType.FIXED_WINDOW);

        long allowed = allowedConcurrently(
                algorithmFor(RateLimitType.FIXED_WINDOW),
                user("user-1", "FREE"),
                requestFor("/api/demo/code-run"),
                40);

        assertEquals(5, allowed,
                "40 simultaneous requests must yield exactly the configured limit");
        assertEquals(5, stateManager.getUserStatus("user-1", "CODE_RUN").getRequestCount());
    }

    // ------------------------------------------------------------------
    // Sliding window
    // ------------------------------------------------------------------

    @Test
    void slidingWindowShouldAllowExactlyTheLimitAndRecordTimestamps() {
        configure("CODE_SUBMIT", "/api/demo/code-submit", "FREE", 3, 30,
                RateLimitType.SLIDING_WINDOW);

        RateLimitingAlgorithms algorithm = algorithmFor(RateLimitType.SLIDING_WINDOW);
        User user = user("user-1", "FREE");
        Request request = requestFor("/api/demo/code-submit");

        assertTrue(algorithm.evaluate(user, request).allowed());
        assertTrue(algorithm.evaluate(user, request).allowed());
        assertTrue(algorithm.evaluate(user, request).allowed());
        assertFalse(algorithm.evaluate(user, request).allowed());

        UserStatus status = stateManager.getUserStatus("user-1", "CODE_SUBMIT");

        assertEquals(3, status.getRequestTimestamps().size());
        status.getRequestTimestamps()
                .forEach(ts -> assertTrue(ts > 0, "timestamps must be real epoch millis"));
    }

    @Test
    void slidingWindowShouldReadmitRequestsAsTimestampsLeaveTheWindow() throws Exception {
        configure("CODE_SUBMIT", "/api/demo/code-submit", "FREE", 2, 1,
                RateLimitType.SLIDING_WINDOW);

        RateLimitingAlgorithms algorithm = algorithmFor(RateLimitType.SLIDING_WINDOW);
        User user = user("user-1", "FREE");
        Request request = requestFor("/api/demo/code-submit");

        assertTrue(algorithm.evaluate(user, request).allowed());
        assertTrue(algorithm.evaluate(user, request).allowed());
        assertFalse(algorithm.evaluate(user, request).allowed());

        Thread.sleep(1_100);

        assertTrue(algorithm.evaluate(user, request).allowed());

        UserStatus status = stateManager.getUserStatus("user-1", "CODE_SUBMIT");
        assertEquals(1, status.getRequestTimestamps().size(),
                "expired timestamps must be pruned from the stored state");
    }

    @Test
    void slidingWindowMustNotExceedTheLimitUnderConcurrency() throws Exception {
        configure("CODE_SUBMIT", "/api/demo/code-submit", "FREE", 4, 60,
                RateLimitType.SLIDING_WINDOW);

        long allowed = allowedConcurrently(
                algorithmFor(RateLimitType.SLIDING_WINDOW),
                user("user-1", "FREE"),
                requestFor("/api/demo/code-submit"),
                40);

        assertEquals(4, allowed);
        assertEquals(4, stateManager.getUserStatus("user-1", "CODE_SUBMIT")
                .getRequestTimestamps().size());
    }

    // ------------------------------------------------------------------
    // Token bucket
    // ------------------------------------------------------------------

    @Test
    void tokenBucketShouldStartFullAndDrain() {
        configure("CODE_RUN", "/api/demo/code-run", "PREMIUM", 3, 60,
                RateLimitType.TOKEN_BUCKET);

        RateLimitingAlgorithms algorithm = algorithmFor(RateLimitType.TOKEN_BUCKET);
        User user = user("user-1", "PREMIUM");
        Request request = requestFor("/api/demo/code-run");

        assertTrue(algorithm.evaluate(user, request).allowed());
        assertTrue(algorithm.evaluate(user, request).allowed());
        assertTrue(algorithm.evaluate(user, request).allowed());

        RateLimitVerdict rejected = algorithm.evaluate(user, request);
        assertFalse(rejected.allowed());
        assertTrue(rejected.retryAfterMillis() > 0);

        UserStatus status = stateManager.getUserStatus("user-1", "CODE_RUN");
        assertTrue(status.getAvailableTokens() < 1.0);
        assertTrue(status.getLastRefillTimestamp() > 0);
    }

    @Test
    void tokenBucketShouldRefillOverTime() throws Exception {
        // 4 tokens per 2 seconds = 2 tokens per second.
        configure("CODE_RUN", "/api/demo/code-run", "PREMIUM", 4, 2,
                RateLimitType.TOKEN_BUCKET);

        RateLimitingAlgorithms algorithm = algorithmFor(RateLimitType.TOKEN_BUCKET);
        User user = user("user-1", "PREMIUM");
        Request request = requestFor("/api/demo/code-run");

        for (int i = 0; i < 4; i++) {
            assertTrue(algorithm.evaluate(user, request).allowed(), "drain " + i);
        }
        assertFalse(algorithm.evaluate(user, request).allowed());

        Thread.sleep(1_100);

        // ~2 tokens should have accrued.
        assertTrue(algorithm.evaluate(user, request).allowed(),
                "tokens must refill while the caller waits");
    }

    @Test
    void tokenBucketShouldNeverExceedCapacityWhileIdle() throws Exception {
        configure("CODE_RUN", "/api/demo/code-run", "PREMIUM", 3, 1,
                RateLimitType.TOKEN_BUCKET);

        RateLimitingAlgorithms algorithm = algorithmFor(RateLimitType.TOKEN_BUCKET);
        User user = user("user-1", "PREMIUM");
        Request request = requestFor("/api/demo/code-run");

        algorithm.evaluate(user, request);

        Thread.sleep(1_500);

        // Idle long enough to overfill; capacity must still cap the bucket.
        assertEquals(2, algorithm.evaluate(user, request).remaining());
    }

    @Test
    void tokenBucketMustNotExceedCapacityUnderConcurrency() throws Exception {
        configure("CODE_RUN", "/api/demo/code-run", "PREMIUM", 6, 600,
                RateLimitType.TOKEN_BUCKET);

        long allowed = allowedConcurrently(
                algorithmFor(RateLimitType.TOKEN_BUCKET),
                user("user-1", "PREMIUM"),
                requestFor("/api/demo/code-run"),
                40);

        // The window is long enough that refill during the burst is negligible.
        assertEquals(6, allowed);
    }

    // ------------------------------------------------------------------
    // Isolation
    // ------------------------------------------------------------------

    @Test
    void differentUsersShouldHaveIndependentState() {
        configure("CODE_RUN", "/api/demo/code-run", "FREE", 2, 60,
                RateLimitType.FIXED_WINDOW);

        RateLimitingAlgorithms algorithm = algorithmFor(RateLimitType.FIXED_WINDOW);
        Request request = requestFor("/api/demo/code-run");

        User first = user("user-1", "FREE");
        User second = user("user-2", "FREE");

        assertTrue(algorithm.evaluate(first, request).allowed());
        assertTrue(algorithm.evaluate(first, request).allowed());
        assertFalse(algorithm.evaluate(first, request).allowed());

        assertTrue(algorithm.evaluate(second, request).allowed(),
                "user-2 must not be affected by user-1 exhausting their quota");
        assertTrue(algorithm.evaluate(second, request).allowed());
        assertFalse(algorithm.evaluate(second, request).allowed());
    }

    @Test
    void differentComponentsShouldHaveIndependentState() {
        configure("CODE_RUN", "/api/demo/code-run", "FREE", 1, 60,
                RateLimitType.FIXED_WINDOW);
        configure("CODE_SUBMIT", "/api/demo/code-submit", "FREE", 1, 60,
                RateLimitType.FIXED_WINDOW);

        RateLimitingAlgorithms algorithm = algorithmFor(RateLimitType.FIXED_WINDOW);
        User user = user("user-1", "FREE");

        assertTrue(algorithm.evaluate(user, requestFor("/api/demo/code-run")).allowed());
        assertFalse(algorithm.evaluate(user, requestFor("/api/demo/code-run")).allowed());

        assertTrue(algorithm.evaluate(user, requestFor("/api/demo/code-submit")).allowed(),
                "a separate component must keep its own counter");
    }

    @Test
    void freeAndPremiumPlansShouldResolveToDifferentLimits() {
        configure("CODE_RUN", "/api/demo/code-run", "FREE", 1, 60,
                RateLimitType.FIXED_WINDOW);
        configure("CODE_RUN", "/api/demo/code-run", "PREMIUM", 3, 60,
                RateLimitType.FIXED_WINDOW);

        RateLimitingAlgorithms algorithm = algorithmFor(RateLimitType.FIXED_WINDOW);
        Request request = requestFor("/api/demo/code-run");

        User free = user("free-user", "FREE");
        User premium = user("premium-user", "PREMIUM");

        assertTrue(algorithm.evaluate(free, request).allowed());
        assertFalse(algorithm.evaluate(free, request).allowed());

        assertTrue(algorithm.evaluate(premium, request).allowed());
        assertTrue(algorithm.evaluate(premium, request).allowed());
        assertTrue(algorithm.evaluate(premium, request).allowed());
        assertFalse(algorithm.evaluate(premium, request).allowed());
    }

    @Test
    void blockedUserShouldBeRejectedWithoutConsumingQuota() {
        configure("CODE_RUN", "/api/demo/code-run", "FREE", 5, 60,
                RateLimitType.FIXED_WINDOW);

        User user = user("user-1", "FREE");
        userManager.addToBlackList(user, "CODE_RUN", 10);

        RateLimitVerdict verdict = algorithmFor(RateLimitType.FIXED_WINDOW)
                .evaluate(user, requestFor("/api/demo/code-run"));

        assertFalse(verdict.allowed());
        assertEquals(RateLimitVerdict.DenialReason.USER_BLOCKED, verdict.reason());
        assertFalse(stateManager.hasState("user-1", "CODE_RUN"),
                "a blocked user must not create rate limit state");
    }

    @Test
    void resettingStateShouldRestoreTheFullQuota() {
        configure("CODE_RUN", "/api/demo/code-run", "FREE", 2, 60,
                RateLimitType.FIXED_WINDOW);

        RateLimitingAlgorithms algorithm = algorithmFor(RateLimitType.FIXED_WINDOW);
        User user = user("user-1", "FREE");
        Request request = requestFor("/api/demo/code-run");

        algorithm.evaluate(user, request);
        algorithm.evaluate(user, request);
        assertFalse(algorithm.evaluate(user, request).allowed());

        assertTrue(stateManager.resetState("user-1", "CODE_RUN"));

        assertTrue(algorithm.evaluate(user, request).allowed(),
                "a reset must start a brand new window");
    }

    @Test
    void resetAllForComponentShouldOnlyTouchThatComponent() {
        configure("CODE_RUN", "/api/demo/code-run", "FREE", 5, 60,
                RateLimitType.FIXED_WINDOW);
        configure("CODE_SUBMIT", "/api/demo/code-submit", "FREE", 5, 60,
                RateLimitType.FIXED_WINDOW);

        RateLimitingAlgorithms algorithm = algorithmFor(RateLimitType.FIXED_WINDOW);

        algorithm.evaluate(user("user-1", "FREE"), requestFor("/api/demo/code-run"));
        algorithm.evaluate(user("user-2", "FREE"), requestFor("/api/demo/code-run"));
        algorithm.evaluate(user("user-1", "FREE"), requestFor("/api/demo/code-submit"));

        assertEquals(2, stateManager.resetAllStateForComponent("CODE_RUN"));

        assertFalse(stateManager.hasState("user-1", "CODE_RUN"));
        assertFalse(stateManager.hasState("user-2", "CODE_RUN"));
        assertTrue(stateManager.hasState("user-1", "CODE_SUBMIT"));
    }
}
