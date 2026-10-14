package com.unispark.ecommerce.shared.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Runs {@code @Scheduled} jobs (SPEC 14) until they move to JobRunr (M11). Off in tests, which call jobs directly.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {
}
