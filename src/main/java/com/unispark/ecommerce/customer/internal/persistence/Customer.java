package com.unispark.ecommerce.customer.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.unispark.ecommerce.shared.TenantScopedEntity;

/** A shopper of one store, known by phone. Rows are inserted by SQL (see CustomerService), so no public constructor. */
@Entity
@Table(name = "customer")
public class Customer extends TenantScopedEntity {

    @Column(nullable = false, updatable = false)
    private String phone;

    private String name;

    @Column(nullable = false)
    private boolean blocked;

    @Column(name = "block_reason")
    private String blockReason;

    @Column(name = "order_count", nullable = false)
    private int orderCount;

    @Column(name = "refused_count", nullable = false)
    private int refusedCount;

    protected Customer() {
    }

    public String getPhone() {
        return phone;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public String getBlockReason() {
        return blockReason;
    }

    public int getOrderCount() {
        return orderCount;
    }

    public int getRefusedCount() {
        return refusedCount;
    }
}
