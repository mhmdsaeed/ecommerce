package com.unispark.ecommerce.shared;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;

import org.hibernate.annotations.TenantId;

/**
 * Base class for every tenant-owned entity (isolation layer 1, SPEC 4.2). Hibernate fills {@code tenant_id} from
 * {@link TenantContext} on insert and adds {@code tenant_id = :current} to every query. The table also needs the
 * RLS policy (layer 2): call {@code enable_tenant_rls('<table>')} in its migration.
 */
@MappedSuperclass
public abstract class TenantScopedEntity extends BaseEntity {

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    public UUID getTenantId() {
        return tenantId;
    }

    @PrePersist
    void requireTenantContext() {
        if (TenantContext.currentTenantId().isEmpty()) {
            throw new IllegalStateException(
                    "Cannot persist " + getClass().getSimpleName() + " outside a tenant context");
        }
    }
}
