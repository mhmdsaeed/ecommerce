package com.unispark.ecommerce.shared.internal.persistence;

import java.util.UUID;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

import com.unispark.ecommerce.shared.TenantContext;

/**
 * Feeds {@link TenantContext} to Hibernate's {@code @TenantId} filter (isolation layer 1, SPEC 4.2).
 *
 * <p>Hibernate refuses to open a session without a tenant id, but sessions without a tenant are normal: store
 * lookup during tenant resolution, platform tables, the event publication registry. Those get {@link #NO_TENANT},
 * the nil UUID, which matches no store. It is deliberately not a root tenant, so the filter stays on and
 * tenant-scoped queries return nothing, just as RLS does.
 */
class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID> {

    static final UUID NO_TENANT = new UUID(0, 0);

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return TenantContext.currentTenantId().orElse(NO_TENANT);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
