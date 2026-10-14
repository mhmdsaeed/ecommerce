package com.unispark.ecommerce.shared;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Cross-tenant database access on the {@code app_platform} role, which bypasses row-level security (SPEC 4.3).
 *
 * <p>Only platform admin and billing services may use it, explicitly; {@code TenancyArchitectureTests} enforces
 * that. Everything else goes through JPA on the tenant-bound DataSource.
 */
public interface PlatformDatabase {

    JdbcClient jdbc();
}
