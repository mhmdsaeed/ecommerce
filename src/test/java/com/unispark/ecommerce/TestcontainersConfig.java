package com.unispark.ecommerce;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * PostgreSQL 16 with the same role setup as docker-compose (docker/postgres/init/01-roles.sh), so
 * tests run as app_user with Flyway as app_owner, exactly like a real deployment. MinIO stands in for S3.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    public static final String DATABASE = "ecommerce";
    public static final String OWNER_USER = "app_owner";
    public static final String OWNER_PASSWORD = "app_owner_test";
    public static final String APP_USER = "app_user";
    public static final String APP_PASSWORD = "app_user_test";
    public static final String PLATFORM_USER = "app_platform";
    public static final String PLATFORM_PASSWORD = "app_platform_test";
    public static final String MEDIA_BUCKET = "media";
    static final String MINIO_IMAGE =
            "chainguard/minio@sha256:2136d593beacf4461187035eca949fe49abd589d5079735025c711ca58fee0c8";

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
            registry.add("spring.flyway.url", () -> jdbcUrl(postgres));
            registry.add("spring.flyway.user", () -> OWNER_USER);
            registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
            registry.add("app.platform-datasource.username", () -> PLATFORM_USER);
            registry.add("app.platform-datasource.password", () -> PLATFORM_PASSWORD);
        };
    }

    /** The same pinned MinIO build as docker-compose; MinIO no longer publishes community images on Docker Hub. */
    @Bean
    MinIOContainer minio() {
        return new MinIOContainer(DockerImageName.parse(MINIO_IMAGE).asCompatibleSubstituteFor("minio/minio"));
    }

    @Bean
    DynamicPropertyRegistrar mediaProperties(MinIOContainer minio) {
        return registry -> {
            registry.add("app.media.endpoint", minio::getS3URL);
            registry.add("app.media.bucket", () -> MEDIA_BUCKET);
            registry.add("app.media.access-key", minio::getUserName);
            registry.add("app.media.secret-key", minio::getPassword);
            registry.add("app.media.public-base-url", () -> minio.getS3URL() + "/" + MEDIA_BUCKET);
            registry.add("app.media.manage-bucket", () -> "true");
        };
    }

    public static String jdbcUrl(PostgreSQLContainer<?> postgres) {
        return "jdbc:postgresql://%s:%d/%s".formatted(
                postgres.getHost(), postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), DATABASE);
    }
}
