package com.unispark.ecommerce.storefront.internal.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.filter.OrderedFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.unispark.ecommerce.shared.RateLimits;

@Configuration(proxyBeanMethods = false)
class StorefrontFilterConfig {

    /** Before Spring Security, so requests over the limit cost no session or CSRF work. */
    static final int RATE_LIMIT_ORDER = OrderedFilter.REQUEST_WRAPPER_FILTER_MAX_ORDER - 105;

    @Bean
    FilterRegistrationBean<StorefrontRateLimitFilter> storefrontRateLimitFilter(RateLimits limits) {
        var registration = new FilterRegistrationBean<>(new StorefrontRateLimitFilter(limits));
        registration.setOrder(RATE_LIMIT_ORDER);
        return registration;
    }
}
