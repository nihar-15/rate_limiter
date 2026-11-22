package com.nihar.nihar.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI rateLimiterOpenAPI() {

        return new OpenAPI().info(new Info()
                .title("Rate Limiter Playground API")
                .version("1.0")
                .description("""
                        Configure rate limiting rules at runtime, call rate limited demo
                        endpoints, and inspect or reset the Redis-backed state.

                        **Typical experiment**

                        1. `POST /api/admin/rules` - configure a rule, e.g. CODE_RUN / FREE /
                           5 requests / 60s / FIXED_WINDOW.
                        2. `POST /api/demo/code-run` with `X-User-Id: user-101` and
                           `X-Subscription-Plan: FREE` - repeat until you get HTTP 429.
                        3. `GET /api/admin/state/user-101/CODE_RUN` - see the counter, window,
                           timestamps or tokens behind the decision.
                        4. `DELETE /api/admin/state/user-101/CODE_RUN` - reset and repeat.

                        **Algorithms**

                        - `FIXED_WINDOW` - a counter that resets every `windowInSeconds`.
                        - `SLIDING_WINDOW` - keeps the timestamps of the last window.
                        - `TOKEN_BUCKET` - a bucket of `limit` tokens refilled continuously
                          over `windowInSeconds`.

                        Decisions execute as atomic Redis Lua scripts, so concurrent requests
                        from one user can never exceed the configured limit.""")
                .license(new License().name("For demonstration use")));
    }
}