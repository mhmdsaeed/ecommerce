package com.unispark.ecommerce.shared;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The tenant (store id) the current thread works for (SPEC 4.1).
 *
 * <p>Set by {@code TenantResolutionFilter} for web requests and explicitly by jobs and event listeners, which
 * must carry the tenant id in their payload. Both isolation layers read it: Hibernate's {@code @TenantId}
 * filter and the {@code app.tenant_id} setting used by PostgreSQL row-level security.
 *
 * <p>The connection is bound to the tenant when it is borrowed, so switch tenants only outside a transaction.
 * Code inside a transaction that enters another tenant keeps the original tenant's connection; both layers then
 * disagree and return nothing, which fails closed but is never what you want.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<UUID> currentTenantId() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static UUID requireTenantId() {
        return currentTenantId().orElseThrow(() -> new IllegalStateException("No tenant in context"));
    }

    /** Makes {@code tenantId} current until the returned scope is closed, then restores the previous tenant. */
    public static Scope enter(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        var previous = CURRENT.get();
        CURRENT.set(tenantId);
        return new Scope(previous);
    }

    public static void runAs(UUID tenantId, Runnable action) {
        try (var scope = enter(tenantId)) {
            action.run();
        }
    }

    public static <T> T callAs(UUID tenantId, Supplier<T> action) {
        try (var scope = enter(tenantId)) {
            return action.get();
        }
    }

    public static final class Scope implements AutoCloseable {

        private final UUID previous;
        private boolean closed;

        private Scope(UUID previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
