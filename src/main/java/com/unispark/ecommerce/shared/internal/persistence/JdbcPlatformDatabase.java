package com.unispark.ecommerce.shared.internal.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.unispark.ecommerce.shared.PlatformDatabase;
import com.zaxxer.hikari.HikariDataSource;

/** {@link PlatformDatabase} on its own small pool of {@code app_platform} connections. */
class JdbcPlatformDatabase implements PlatformDatabase, AutoCloseable {

    private final HikariDataSource pool;
    private final JdbcClient jdbc;

    JdbcPlatformDatabase(HikariDataSource pool) {
        this.pool = pool;
        this.jdbc = JdbcClient.create(pool);
    }

    @Override
    public JdbcClient jdbc() {
        return jdbc;
    }

    @Override
    public void close() {
        pool.close();
    }
}
