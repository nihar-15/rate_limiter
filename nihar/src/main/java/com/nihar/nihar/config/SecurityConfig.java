package com.nihar.nihar.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Two deliberately different security postures.
 *
 * <p>The {@code local} profile (the default when nothing else is active) is the
 * playground: Swagger and every API are open so experiments need no credentials.
 * Any other profile locks the administration and documentation surface behind
 * authentication, and only the demo endpoints stay open. Nothing here weakens
 * security globally just to make Swagger reachable.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] DOCS = {
            "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**"
    };

    /** Local development: open playground, CSRF off so curl and scripts work. */
    @Bean
    @Profile("local")
    public SecurityFilterChain localFilterChain(HttpSecurity http) throws Exception {

        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    /**
     * Everything else: demo endpoints public, administration and docs
     * authenticated with HTTP Basic (the credentials Spring Boot generates, or
     * whatever {@code spring.security.user.*} is configured to).
     */
    @Bean
    @Profile("!local")
    public SecurityFilterChain defaultFilterChain(HttpSecurity http) throws Exception {

        return http
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/demo/**"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/demo/**").permitAll()
                        .requestMatchers("/api/admin/**").authenticated()
                        .requestMatchers(DOCS).authenticated()
                        .anyRequest().authenticated())
                .httpBasic(basic -> {})
                .build();
    }
}