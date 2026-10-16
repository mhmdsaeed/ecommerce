package com.unispark.ecommerce.shared.internal.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.https-only", havingValue = "true")
class HttpsConfig {

    /** First of all filters: nothing else needs to run for a request that is only redirected. */
    @Bean
    FilterRegistrationBean<HttpsRedirectFilter> httpsRedirectFilter() {
        var registration = new FilterRegistrationBean<>(new HttpsRedirectFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
