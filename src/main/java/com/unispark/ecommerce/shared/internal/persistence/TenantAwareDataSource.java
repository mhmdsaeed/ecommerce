package com.unispark.ecommerce.shared.internal.persistence;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import com.unispark.ecommerce.shared.TenantContext;

/**
 * Binds every borrowed connection to the current tenant for PostgreSQL row-level security (isolation layer 2,
 * SPEC 4.3). On borrow, {@code app.tenant_id} is set to the {@link TenantContext} tenant, or to {@code ''} when
 * there is none, which the RLS policies treat as "no rows". On close, it is cleared before the connection goes
 * back to the pool.
 *
 * <p>Setting it on every borrow, not only clearing it on return, is what makes this safe: a connection whose
 * reset failed still cannot carry one tenant's setting into another tenant's request.
 */
class TenantAwareDataSource extends DelegatingDataSource implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareDataSource.class);

    static final String NO_TENANT = "";
    private static final String SET_TENANT = "SELECT set_config('app.tenant_id', ?, false)";

    TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return bindToCurrentTenant(obtainTargetDataSource().getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return bindToCurrentTenant(obtainTargetDataSource().getConnection(username, password));
    }

    private static Connection bindToCurrentTenant(Connection connection) throws SQLException {
        try {
            setTenant(connection, TenantContext.currentTenantId().map(UUID::toString).orElse(NO_TENANT));
            // A session-level setting made inside a transaction is undone if that transaction rolls back,
            // so make it stick before the caller starts its own work.
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
        return (Connection) Proxy.newProxyInstance(
                TenantAwareDataSource.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                new ResetOnClose(connection));
    }

    private static void setTenant(Connection connection, String tenantId) throws SQLException {
        try (var statement = connection.prepareStatement(SET_TENANT)) {
            statement.setString(1, tenantId);
            statement.execute();
        }
    }

    @Override
    public void close() throws Exception {
        if (obtainTargetDataSource() instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    private static final class ResetOnClose implements InvocationHandler {

        private final Connection target;
        private boolean closed;

        ResetOnClose(Connection target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
                close();
                return null;
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private void close() throws SQLException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (!target.isClosed()) {
                    setTenant(target, NO_TENANT);
                }
            } catch (SQLException e) {
                // Harmless: the next borrow overwrites the setting before any query runs.
                log.warn("Could not clear app.tenant_id before returning a connection to the pool", e);
            } finally {
                target.close();
            }
        }
    }
}
