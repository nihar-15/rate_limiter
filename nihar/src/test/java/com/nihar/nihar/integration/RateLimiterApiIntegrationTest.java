package com.nihar.nihar.integration;

import com.nihar.nihar.managers.RateLimitStateManager;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End to end test of the running application: rules configured through the REST
 * API, demo endpoints enforcing them against a real Redis, and the state
 * inspection and reset APIs reflecting what actually happened.
 *
 * <p>Each test configures the rule it needs with PUT first, so the tests are
 * independent of each other and of execution order.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class RateLimiterApiIntegrationTest {

    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @BeforeAll
    static void startRedis() {
        redis.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RateLimitStateManager stateManager;

    @BeforeEach
    void clearState() {
        stateManager.resetAllState();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String ruleJson(String component, String method, String path,
                            String plan, int limit, int window, String algorithm) {
        return """
                {
                  "component": "%s",
                  "httpMethod": "%s",
                  "apiPath": "%s",
                  "plan": "%s",
                  "limit": %d,
                  "windowInSeconds": %d,
                  "algorithm": "%s"
                }
                """.formatted(component, method, path, plan, limit, window, algorithm);
    }

    private HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    /** Configures a rule, creating it or replacing an existing one. */
    private void putRule(String component, String method, String path,
                         String plan, int limit, int window, String algorithm) {

        String body = ruleJson(component, method, path, plan, limit, window, algorithm);

        ResponseEntity<String> updated = rest.exchange(
                "/api/admin/rules?resetState=true", HttpMethod.PUT, json(body), String.class);

        if (updated.getStatusCode() == HttpStatus.NOT_FOUND) {
            ResponseEntity<String> created = rest.exchange(
                    "/api/admin/rules?resetState=true", HttpMethod.POST, json(body), String.class);
            assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        } else {
            assertEquals(HttpStatus.OK, updated.getStatusCode(), updated.getBody());
        }
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> callDemo(String path, String userId, String plan) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", userId);
        if (plan != null) {
            headers.set("X-Subscription-Plan", plan);
        }
        HttpMethod method = path.endsWith("/status") ? HttpMethod.GET : HttpMethod.POST;

        return rest.exchange(path, method, new HttpEntity<>(headers), Map.class);
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    @Test
    void shouldLoadTheRulesDeclaredInConfigurationAtStartup() {
        ResponseEntity<List> response =
                rest.getForEntity("/api/admin/rules/CODE_RUN", List.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        List<Map<String, Object>> rules = response.getBody();
        assertNotNull(rules);
        assertTrue(rules.size() >= 2, "config seeds CODE_RUN for FREE and PREMIUM");
        assertTrue(rules.stream().anyMatch(r ->
                "FREE".equals(r.get("plan")) && "/api/demo/code-run".equals(r.get("apiPath"))));
        assertTrue(rules.stream().anyMatch(r -> "PREMIUM".equals(r.get("plan"))));
    }

    @Test
    void shouldCreateAndDeleteAComponentThroughTheApi() {
        String body = ruleJson("ITEST_TEMP", "POST", "/api/demo/itest-temp",
                "FREE", 7, 60, "FIXED_WINDOW");

        ResponseEntity<Map> created = rest.exchange(
                "/api/admin/rules", HttpMethod.POST, json(body), Map.class);

        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertEquals("ITEST_TEMP", created.getBody().get("component"));
        assertEquals(7, created.getBody().get("limit"));

        // Creating the same rule twice is a conflict.
        ResponseEntity<Map> duplicate = rest.exchange(
                "/api/admin/rules", HttpMethod.POST, json(body), Map.class);
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode());

        ResponseEntity<Map> deleted = rest.exchange(
                "/api/admin/rules/ITEST_TEMP", HttpMethod.DELETE, null, Map.class);
        assertEquals(HttpStatus.OK, deleted.getStatusCode());
        assertEquals(1, deleted.getBody().get("rulesRemoved"));

        ResponseEntity<Map> gone = rest.getForEntity("/api/admin/rules/ITEST_TEMP", Map.class);
        assertEquals(HttpStatus.NOT_FOUND, gone.getStatusCode());
    }

    @Test
    void shouldRejectAnInvalidRule() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/admin/rules", HttpMethod.POST,
                json(ruleJson("ITEST_BAD", "POST", "/api/demo/itest-bad",
                        "FREE", 0, 60, "FIXED_WINDOW")),
                Map.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_FAILED", response.getBody().get("error"));
    }

    @Test
    void shouldReturn404WhenNoRuleCoversThePlan() {
        ResponseEntity<Map> response =
                callDemo("/api/demo/code-run", "itest-user", "ENTERPRISE");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("RULE_NOT_FOUND", response.getBody().get("error"));
    }

    // ------------------------------------------------------------------
    // Runtime enforcement
    // ------------------------------------------------------------------

    @Test
    void shouldEnforceTheConfiguredFixedWindowLimitOverHttp() {
        putRule("CODE_RUN", "POST", "/api/demo/code-run",
                "FREE", 3, 60, "FIXED_WINDOW");

        for (int i = 0; i < 3; i++) {
            ResponseEntity<Map> allowed =
                    callDemo("/api/demo/code-run", "itest-fw", "FREE");

            assertEquals(HttpStatus.OK, allowed.getStatusCode(), "request " + i);
            assertEquals(true, allowed.getBody().get("allowed"));
            assertEquals("CODE_RUN", allowed.getBody().get("component"));
            assertEquals("FIXED_WINDOW", allowed.getBody().get("algorithm"));
            assertEquals(3, allowed.getBody().get("limit"));
            assertEquals(60, allowed.getBody().get("windowInSeconds"));
            assertEquals("Request allowed", allowed.getBody().get("message"));
            assertEquals(2 - i, allowed.getBody().get("remaining"));
        }

        ResponseEntity<Map> rejected = callDemo("/api/demo/code-run", "itest-fw", "FREE");

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getStatusCode());
        assertEquals(false, rejected.getBody().get("allowed"));
        assertEquals("Rate limit exceeded", rejected.getBody().get("message"));
        assertEquals(0, rejected.getBody().get("remaining"));
        assertNotNull(rejected.getHeaders().getFirst("Retry-After"));
        assertEquals("3", rejected.getHeaders().getFirst("X-RateLimit-Limit"));
    }

    @Test
    void shouldPersistStateInRedisAndExposeItThroughTheStateApi() {
        putRule("CODE_RUN", "POST", "/api/demo/code-run",
                "FREE", 5, 60, "FIXED_WINDOW");

        callDemo("/api/demo/code-run", "itest-state", "FREE");
        callDemo("/api/demo/code-run", "itest-state", "FREE");

        ResponseEntity<Map> state = rest.getForEntity(
                "/api/admin/state/itest-state/CODE_RUN", Map.class);

        assertEquals(HttpStatus.OK, state.getStatusCode());
        assertEquals(true, state.getBody().get("exists"));
        assertEquals("ratelimit:itest-state:CODE_RUN", state.getBody().get("redisKey"));
        assertEquals(2, state.getBody().get("requestCount"));
        assertTrue(((Number) state.getBody().get("windowStartTimestamp")).longValue() > 0);
        assertTrue(((Number) state.getBody().get("ttlSeconds")).longValue() > 0,
                "state must carry a TTL so it cannot leak");

        // And the same state is visible through the manager, i.e. it really is in Redis.
        assertEquals(2, stateManager.getUserStatus("itest-state", "CODE_RUN").getRequestCount());
    }

    @Test
    void shouldRecordTimestampsForSlidingWindow() {
        putRule("CODE_SUBMIT", "POST", "/api/demo/code-submit",
                "FREE", 2, 30, "SLIDING_WINDOW");

        callDemo("/api/demo/code-submit", "itest-sw", "FREE");
        callDemo("/api/demo/code-submit", "itest-sw", "FREE");

        ResponseEntity<Map> state = rest.getForEntity(
                "/api/admin/state/itest-sw/CODE_SUBMIT", Map.class);

        List<?> timestamps = (List<?>) state.getBody().get("requestTimestamps");
        assertEquals(2, timestamps.size());
    }

    @Test
    void shouldTrackTokensForTokenBucket() {
        putRule("CODE_RUN", "POST", "/api/demo/code-run",
                "PREMIUM", 4, 600, "TOKEN_BUCKET");

        callDemo("/api/demo/code-run", "itest-tb", "PREMIUM");

        ResponseEntity<Map> state = rest.getForEntity(
                "/api/admin/state/itest-tb/CODE_RUN", Map.class);

        double tokens = ((Number) state.getBody().get("availableTokens")).doubleValue();

        assertTrue(tokens > 2.9 && tokens <= 3.1,
                "one of four tokens consumed, was " + tokens);
        assertTrue(((Number) state.getBody().get("lastRefillTimestamp")).longValue() > 0);
    }

    @Test
    void shouldStartAFreshWindowAfterTheConfiguredWindowElapses() throws Exception {
        putRule("CODE_RUN", "POST", "/api/demo/code-run",
                "FREE", 2, 1, "FIXED_WINDOW");

        assertEquals(HttpStatus.OK,
                callDemo("/api/demo/code-run", "itest-expiry", "FREE").getStatusCode());
        assertEquals(HttpStatus.OK,
                callDemo("/api/demo/code-run", "itest-expiry", "FREE").getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS,
                callDemo("/api/demo/code-run", "itest-expiry", "FREE").getStatusCode());

        Thread.sleep(1_100);

        assertEquals(HttpStatus.OK,
                callDemo("/api/demo/code-run", "itest-expiry", "FREE").getStatusCode(),
                "the limit must lift once the window expires");
    }

    @Test
    void shouldKeepUsersIndependent() {
        putRule("CODE_RUN", "POST", "/api/demo/code-run",
                "FREE", 1, 60, "FIXED_WINDOW");

        assertEquals(HttpStatus.OK,
                callDemo("/api/demo/code-run", "itest-a", "FREE").getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS,
                callDemo("/api/demo/code-run", "itest-a", "FREE").getStatusCode());

        assertEquals(HttpStatus.OK,
                callDemo("/api/demo/code-run", "itest-b", "FREE").getStatusCode(),
                "a second user must have their own quota");
    }

    @Test
    void freeAndPremiumShouldGetDifferentLimits() {
        putRule("CODE_RUN", "POST", "/api/demo/code-run",
                "FREE", 1, 60, "FIXED_WINDOW");
        putRule("CODE_RUN", "POST", "/api/demo/code-run",
                "PREMIUM", 3, 60, "TOKEN_BUCKET");

        assertEquals(HttpStatus.OK,
                callDemo("/api/demo/code-run", "itest-plan", "FREE").getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS,
                callDemo("/api/demo/code-run", "itest-plan", "FREE").getStatusCode());

        // Same user id, premium plan: a different rule, a different component state
        // is not involved - the component is the same, so use a different user.
        for (int i = 0; i < 3; i++) {
            ResponseEntity<Map> response =
                    callDemo("/api/demo/code-run", "itest-plan-premium", "PREMIUM");
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals("TOKEN_BUCKET", response.getBody().get("algorithm"));
            assertEquals(3, response.getBody().get("limit"));
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS,
                callDemo("/api/demo/code-run", "itest-plan-premium", "PREMIUM").getStatusCode());
    }

    @Test
    void shouldApplyARuleChangeWithoutRestarting() {
        putRule("CODE_RUN", "POST", "/api/demo/code-run",
                "FREE", 1, 60, "FIXED_WINDOW");

        assertEquals(HttpStatus.OK,
                callDemo("/api/demo/code-run", "itest-change", "FREE").getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS,
                callDemo("/api/demo/code-run", "itest-change", "FREE").getStatusCode());

        // Raise the limit at runtime; the existing counter of 1 is kept.
        ResponseEntity<Map> updated = rest.exchange(
                "/api/admin/rules", HttpMethod.PUT,
                json(ruleJson("CODE_RUN", "POST", "/api/demo/code-run",
                        "FREE", 3, 60, "FIXED_WINDOW")),
                Map.class);
        assertEquals(HttpStatus.OK, updated.getStatusCode());

        ResponseEntity<Map> afterChange =
                callDemo("/api/demo/code-run", "itest-change", "FREE");

        assertEquals(HttpStatus.OK, afterChange.getStatusCode(),
                "the new limit must take effect immediately");
        assertEquals(3, afterChange.getBody().get("limit"));
        assertEquals(1, afterChange.getBody().get("remaining"),
                "the pre-existing counter is reinterpreted under the new limit");
    }

    @Test
    void shouldReportTheCallersQuotasOnStatus() {
        putRule("STATUS_CHECK", "GET", "/api/demo/status",
                "FREE", 10, 60, "TOKEN_BUCKET");
        putRule("CODE_RUN", "POST", "/api/demo/code-run",
                "FREE", 5, 60, "FIXED_WINDOW");

        callDemo("/api/demo/code-run", "itest-status", "FREE");

        ResponseEntity<Map> status = callDemo("/api/demo/status", "itest-status", "FREE");

        assertEquals(HttpStatus.OK, status.getStatusCode());

        Map<String, Object> rateLimit = (Map<String, Object>) status.getBody().get("rateLimit");
        assertEquals("STATUS_CHECK", rateLimit.get("component"));
        assertEquals(true, rateLimit.get("allowed"));

        List<Map<String, Object>> quotas =
                (List<Map<String, Object>>) status.getBody().get("quotas");

        Map<String, Object> codeRun = quotas.stream()
                .filter(q -> "CODE_RUN".equals(q.get("component")))
                .findFirst()
                .orElseThrow();

        assertEquals(4, codeRun.get("remaining"), "one of five already consumed");
        assertEquals(true, codeRun.get("stateExists"));
    }

    // ------------------------------------------------------------------
    // State reset
    // ------------------------------------------------------------------

    @Test
    void shouldResetStateForOneUserAndComponent() {
        putRule("CODE_RUN", "POST", "/api/demo/code-run",
                "FREE", 1, 60, "FIXED_WINDOW");

        callDemo("/api/demo/code-run", "itest-reset", "FREE");
        callDemo("/api/demo/code-run", "itest-reset-other", "FREE");

        assertEquals(HttpStatus.TOO_MANY_REQUESTS,
                callDemo("/api/demo/code-run", "itest-reset", "FREE").getStatusCode());

        ResponseEntity<Map> reset = rest.exchange(
                "/api/admin/state/itest-reset/CODE_RUN", HttpMethod.DELETE, null, Map.class);

        assertEquals(HttpStatus.OK, reset.getStatusCode());
        assertEquals(1, reset.getBody().get("keysRemoved"));

        assertEquals(HttpStatus.OK,
                callDemo("/api/demo/code-run", "itest-reset", "FREE").getStatusCode());

        // The other user's state was untouched and is still exhausted.
        assertEquals(HttpStatus.TOO_MANY_REQUESTS,
                callDemo("/api/demo/code-run", "itest-reset-other", "FREE").getStatusCode());
    }

    @Test
    void shouldResetAllPlaygroundState() {
        putRule("CODE_RUN", "POST", "/api/demo/code-run",
                "FREE", 1, 60, "FIXED_WINDOW");

        callDemo("/api/demo/code-run", "itest-wipe-1", "FREE");
        callDemo("/api/demo/code-run", "itest-wipe-2", "FREE");

        ResponseEntity<Map> reset = rest.exchange(
                "/api/admin/state", HttpMethod.DELETE, null, Map.class);

        assertEquals(HttpStatus.OK, reset.getStatusCode());
        assertTrue(((Number) reset.getBody().get("keysRemoved")).longValue() >= 2);

        assertEquals(HttpStatus.OK,
                callDemo("/api/demo/code-run", "itest-wipe-1", "FREE").getStatusCode());
    }

    // ------------------------------------------------------------------
    // Concurrency through the full HTTP stack
    // ------------------------------------------------------------------

    @Test
    void concurrentRequestsFromOneUserMustNotExceedTheLimit() throws Exception {
        putRule("CODE_RUN", "POST", "/api/demo/code-run",
                "FREE", 10, 60, "FIXED_WINDOW");

        int concurrency = 50;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CyclicBarrier barrier = new CyclicBarrier(concurrency);

        try {
            List<Callable<HttpStatus>> tasks = IntStream.range(0, concurrency)
                    .mapToObj(i -> (Callable<HttpStatus>) () -> {
                        barrier.await(30, TimeUnit.SECONDS);
                        return (HttpStatus) callDemo(
                                "/api/demo/code-run", "itest-concurrent", "FREE")
                                .getStatusCode();
                    })
                    .toList();

            List<Future<HttpStatus>> futures = pool.invokeAll(tasks, 120, TimeUnit.SECONDS);

            long allowed = 0;
            long limited = 0;
            for (Future<HttpStatus> future : futures) {
                HttpStatus status = future.get();
                if (status == HttpStatus.OK) {
                    allowed++;
                } else if (status == HttpStatus.TOO_MANY_REQUESTS) {
                    limited++;
                } else {
                    fail("unexpected status " + status);
                }
            }

            assertEquals(10, allowed,
                    "50 simultaneous requests must yield exactly the configured limit");
            assertEquals(40, limited);
            assertEquals(10, stateManager
                    .getUserStatus("itest-concurrent", "CODE_RUN").getRequestCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentRequestsFromDifferentUsersMustEachGetTheirOwnQuota() throws Exception {
        putRule("CODE_RUN", "POST", "/api/demo/code-run",
                "FREE", 2, 60, "FIXED_WINDOW");

        int users = 10;
        int perUser = 5;
        int total = users * perUser;

        ExecutorService pool = Executors.newFixedThreadPool(total);
        CyclicBarrier barrier = new CyclicBarrier(total);

        try {
            List<Callable<HttpStatus>> tasks = IntStream.range(0, total)
                    .mapToObj(i -> (Callable<HttpStatus>) () -> {
                        String user = "itest-multi-" + (i % users);
                        barrier.await(30, TimeUnit.SECONDS);
                        return (HttpStatus) callDemo("/api/demo/code-run", user, "FREE")
                                .getStatusCode();
                    })
                    .toList();

            long allowed = 0;
            for (Future<HttpStatus> future : pool.invokeAll(tasks, 120, TimeUnit.SECONDS)) {
                if (future.get() == HttpStatus.OK) {
                    allowed++;
                }
            }

            assertEquals(users * 2, allowed,
                    "each of the " + users + " users must get exactly 2 allowed requests");
        } finally {
            pool.shutdownNow();
        }
    }
}
