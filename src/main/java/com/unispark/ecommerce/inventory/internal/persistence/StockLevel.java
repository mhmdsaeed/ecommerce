package com.unispark.ecommerce.inventory.internal.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.unispark.ecommerce.inventory.StockLevels;
import com.unispark.ecommerce.shared.TenantScopedEntity;

/**
 * A tracked variant's stock. Read through JPA; every write is a conditional SQL update in {@code InventoryService},
 * so concurrent changes cannot oversell.
 */
@Entity
@Table(name = "stock_level")
public class StockLevel extends TenantScopedEntity {

    @Column(name = "variant_id", nullable = false, updatable = false)
    private UUID variantId;

    @Column(name = "on_hand", nullable = false)
    private int onHand;

    @Column(nullable = false)
    private int reserved;

    protected StockLevel() {
    }

    public UUID getVariantId() {
        return variantId;
    }

    public int getOnHand() {
        return onHand;
    }

    public int getReserved() {
        return reserved;
    }

    public StockLevels.Level toLevel() {
        return new StockLevels.Level(variantId, onHand, reserved);
    }
}
