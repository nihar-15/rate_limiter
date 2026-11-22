package com.nihar.nihar.config;

import com.nihar.nihar.service.RuleService;
import com.nihar.nihar.web.dto.RuleRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds the rule registry from {@code ratelimiter.rules} at startup.
 *
 * <p>This is what keeps demo rules out of the controllers: the shipped rules
 * live in {@code application-local.yml} and can be replaced at runtime through
 * the admin APIs. A seed rule that clashes with an already configured one is
 * logged and skipped rather than failing startup.</p>
 */
@Component
public class RuleBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RuleBootstrapRunner.class);

    private final RateLimiterProperties properties;
    private final RuleService ruleService;

    public RuleBootstrapRunner(
            RateLimiterProperties properties, RuleService ruleService) {

        this.properties = properties;
        this.ruleService = ruleService;
    }

    @Override
    public void run(ApplicationArguments args) {

        if (properties.getRules().isEmpty()) {
            log.info("No rules configured under 'ratelimiter.rules'; "
                    + "configure them via POST /api/admin/rules");
            return;
        }

        int loaded = 0;

        for (RateLimiterProperties.RuleDefinition definition : properties.getRules()) {
            try {
                ruleService.createOrReplace(toRequest(definition));
                loaded++;
            } catch (RuntimeException ex) {
                log.warn("Skipping configured rule for component '{}' plan '{}': {}",
                        definition.getComponent(), definition.getPlan(), ex.getMessage());
            }
        }

        log.info("Loaded {} rate limiting rule(s) from configuration", loaded);
    }

    private RuleRequest toRequest(RateLimiterProperties.RuleDefinition definition) {

        RuleRequest request = new RuleRequest();
        request.setComponent(definition.getComponent());
        request.setHttpMethod(definition.getHttpMethod());
        request.setApiPath(definition.getApiPath());
        request.setPlan(definition.getPlan());
        request.setLimit(definition.getLimit());
        request.setWindowInSeconds(definition.getWindowInSeconds());
        request.setAlgorithm(definition.getAlgorithm());
        return request;
    }
}
