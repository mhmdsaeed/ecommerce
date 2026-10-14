package com.unispark.ecommerce;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.unispark.ecommerce.shared.TenantScopedEntity;

/** Test-only tenant entity on the {@code isolation_probe} table (src/test/resources/db/testmigration). */
@Entity
@Table(name = "isolation_probe")
public class IsolationProbe extends TenantScopedEntity {

    @Column(nullable = false)
    private String label;

    protected IsolationProbe() {
    }

    public IsolationProbe(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
