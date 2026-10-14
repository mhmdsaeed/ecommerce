package com.unispark.ecommerce.shared.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

import com.unispark.ecommerce.TestcontainersConfig;
import com.unispark.ecommerce.shared.TenantContext;

/**
 * Borrow/return behavior of {@link TenantAwareDataSource} on a single physical connection, so a setting left
 * behind by one borrower would be visible to the next.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class TenantAwareDataSourceIT {

    static final UUID TENANT_A = UUID.fromString("0190a1b2-0000-7000-8000-00000000000a");
    static final UUID TENANT_B = UUID.fromString("0190a1b2-0000-7000-8000-00000000000b");

    @Autowired
    PostgreSQLContainer<?> postgres;

    SingleConnectionDataSource physical;
    TenantAwareDataSource dataSource;

    @BeforeEach
    void openOnePhysicalConnection() {
        physical = new SingleConnectionDataSource(TestcontainersConfig.jdbcUrl(postgres),
                TestcontainersConfig.APP_USER, TestcontainersConfig.APP_PASSWORD, true);
        dataSource = new TenantAwareDataSource(physical);
    }

    @AfterEach
    void closePhysicalConnection() {
        physical.destroy();
    }

    @Test
    void bindsABorrowedConnectionToTheCurrentTenant() throws SQLException {
        try (var scope = TenantContext.enter(TENANT_A); var connection = dataSource.getConnection()) {
            assertThat(tenantSetting(connection)).isEqualTo(TENANT_A.toString());
        }
    }

    @Test
    void bindsToNoTenantOutsideATenantContext() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            assertThat(tenantSetting(connection)).isEmpty();
        }
    }

    @Test
    void clearsTheTenantWhenTheConnectionIsReturned() throws SQLException {
        try (var scope = TenantContext.enter(TENANT_A); var connection = dataSource.getConnection()) {
            assertThat(tenantSetting(connection)).isEqualTo(TENANT_A.toString());
        }

        assertThat(tenantSetting(physical.getConnection())).isEmpty();
    }

    @Test
    void overwritesAStaleTenantOnBorrow() throws SQLException {
        setTenantDirectly(physical.getConnection(), TENANT_B);

        try (var scope = TenantContext.enter(TENANT_A); var connection = dataSource.getConnection()) {
            assertThat(tenantSetting(connection)).isEqualTo(TENANT_A.toString());
        }
        setTenantDirectly(physical.getConnection(), TENANT_B);
        try (var connection = dataSource.getConnection()) {
            assertThat(tenantSetting(connection)).isEmpty();
        }
    }

    @Test
    void keepsTheTenantWhenTheFirstTransactionRollsBack() throws SQLException {
        physical.getConnection().setAutoCommit(false);
        try {
            try (var scope = TenantContext.enter(TENANT_A); var connection = dataSource.getConnection()) {
                connection.createStatement().execute("SELECT 1");
                connection.rollback();

                assertThat(tenantSetting(connection)).isEqualTo(TENANT_A.toString());
            }
        } finally {
            physical.getConnection().rollback();
            physical.getConnection().setAutoCommit(true);
        }
    }

    @Test
    void closingTwiceResetsOnlyOnce() throws SQLException {
        var connection = dataSource.getConnection();
        connection.close();
        connection.close();

        assertThat(physical.getConnection().isClosed()).isFalse();
    }

    private static String tenantSetting(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT current_setting('app.tenant_id', true)")) {
            result.next();
            var value = result.getString(1);
            return value == null ? "" : value;
        }
    }

    private static void setTenantDirectly(Connection connection, UUID tenantId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
            statement.setString(1, tenantId.toString());
            statement.execute();
        }
    }
}
