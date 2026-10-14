package com.unispark.ecommerce.shared.internal.persistence;

import javax.sql.DataSource;

import org.hibernate.cfg.AvailableSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.unispark.ecommerce.shared.PlatformDatabase;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The two database entry points (SPEC 4.3):
 * <ul>
 *   <li>the application DataSource: {@code app_user} (NOBYPASSRLS), every connection bound to the current tenant;
 *   <li>{@link PlatformDatabase}: {@code app_platform} (BYPASSRLS), for platform admin and billing only.
 * </ul>
 * The platform pool is deliberately not a {@link DataSource} bean, so it can never be injected by type by
 * accident. Flyway connects on its own as the schema owner ({@code spring.flyway.url/user/password}).
 */
@Configuration(proxyBeanMethods = false)
class PersistenceConfig {

    @Bean
    DataSource dataSource(DataSourceProperties properties, Environment environment) {
        var pool = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(pool));
        if (pool.getPoolName() == null) {
            pool.setPoolName("tenant-pool");
        }
        return new TenantAwareDataSource(pool);
    }

    @Bean
    PlatformDatabase platformDatabase(
            DataSourceProperties properties,
            @Value("${app.platform-datasource.username}") String username,
            @Value("${app.platform-datasource.password}") String password,
            @Value("${app.platform-datasource.maximum-pool-size:4}") int maximumPoolSize) {
        var pool = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .username(username)
                .password(password)
                .build();
        pool.setPoolName("platform-pool");
        pool.setMaximumPoolSize(maximumPoolSize);
        return new JdbcPlatformDatabase(pool);
    }

    @Bean
    HibernatePropertiesCustomizer tenantIdentifierResolver() {
        return properties -> properties.put(
                AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, new TenantIdentifierResolver());
    }
}
