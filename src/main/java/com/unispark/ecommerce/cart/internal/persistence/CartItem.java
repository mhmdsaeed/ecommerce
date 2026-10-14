package com.unispark.ecommerce.cart.internal.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.unispark.ecommerce.shared.TenantScopedEntity;

@Entity
@Table(name = "cart_item")
public class CartItem extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false, updatable = false)
    private Cart cart;

    @Column(name = "variant_id", nullable = false, updatable = false)
    private UUID variantId;

    @Column(nullable = false)
    private int quantity;

    protected CartItem() {
    }

    CartItem(Cart cart, UUID variantId, int quantity) {
        this.cart = cart;
        this.variantId = variantId;
        this.quantity = quantity;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public int getQuantity() {
        return quantity;
    }

    void setQuantity(int quantity) {
        this.quantity = quantity;
    }
}
