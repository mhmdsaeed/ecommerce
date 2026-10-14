package com.unispark.ecommerce.tenant.internal.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.filter.OrderedFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import com.unispark.ecommerce.tenant.StoreLookup;

@Configuration(proxyBeanMethods = false)
class TenantWebConfig {

    /** Before Spring Security (REQUEST_WRAPPER_FILTER_MAX_ORDER - 100), so security rules can see the tenant. */
    static final int TENANT_RESOLUTION_ORDER = OrderedFilter.REQUEST_WRAPPER_FILTER_MAX_ORDER - 110;

    @Bean
    StoreHosts storeHosts(@Value("${app.base-domain}") String baseDomain) {
        return new StoreHosts(baseDomain);
    }

    /**
     * {@code @Lazy}: the web server creates filters before the rest of the context, and resolving the store
     * lookup eagerly would drag JPA and the DataSource into server startup.
     */
    @Bean
    FilterRegistrationBean<TenantResolutionFilter> tenantResolutionFilter(StoreHosts hosts, @Lazy StoreLookup stores) {
        var registration = new FilterRegistrationBean<>(new TenantResolutionFilter(hosts, stores));
        registration.setOrder(TENANT_RESOLUTION_ORDER);
        return registration;
    }
}
