package com.unispark.ecommerce;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * PostgreSQL 16 with the same role setup as docker-compose (docker/postgres/init/01-roles.sh), so
 * tests run as app_user with Flyway as app_owner, exactly like a real deployment.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    static final String DATABASE = "ecommerce";
    static final String OWNER_USER = "app_owner";
    static final String OWNER_PASSWORD = "app_owner_test";
    static final String APP_USER = "app_user";
    static final String APP_PASSWORD = "app_user_test";
    static final String PLATFORM_USER = "app_platform";
    static final String PLATFORM_PASSWORD = "app_platform_test";

    @Bean
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16")
                .withEnv("DB_NAME", DATABASE)
                .withEnv("DB_OWNER_USER", OWNER_USER)
                .withEnv("DB_OWNER_PASSWORD", OWNER_PASSWORD)
                .withEnv("DB_APP_USER", APP_USER)
                .withEnv("DB_APP_PASSWORD", APP_PASSWORD)
                .withEnv("DB_PLATFORM_USER", PLATFORM_USER)
                .withEnv("DB_PLATFORM_PASSWORD", PLATFORM_PASSWORD)
                .withCopyFileToContainer(
                        MountableFile.forHostPath("docker/postgres/init/01-roles.sh", 0755),
                        "/docker-entrypoint-initdb.d/01-roles.sh");
    }

    @Bean
    DynamicPropertyRegistrar databaseProperties(PostgreSQLContainer<?> postgres) {
        return registry -> {
            registry.add("spring.datasource.url", () -> jdbcUrl(postgres));
            registry.add("spring.datasource.username", () -> APP_USER);
            registry.add("spring.datasource.password", () -> APP_PASSWORD);
            registry.add("spring.flyway.user", () -> OWNER_USER);
            registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
        };
    }

    static String jdbcUrl(PostgreSQLContainer<?> postgres) {
        return "jdbc:postgresql://%s:%d/%s".formatted(
                postgres.getHost(), postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), DATABASE);
    }
}
