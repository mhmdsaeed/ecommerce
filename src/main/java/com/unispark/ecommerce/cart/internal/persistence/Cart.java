package com.unispark.ecommerce.cart.internal.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import com.unispark.ecommerce.shared.TenantScopedEntity;

/** A shopper's cart. {@code sessionKey} is the SHA-256 of the token in the shopper's cookie. */
@Entity
@Table(name = "cart")
public class Cart extends TenantScopedEntity {

    @Column(name = "session_key", nullable = false, updatable = false)
    private String sessionKey;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt, id")
    private List<CartItem> items = new ArrayList<>();

    protected Cart() {
    }

    public Cart(String sessionKey, Instant expiresAt) {
        this.sessionKey = sessionKey;
        this.expiresAt = expiresAt;
    }

    public List<CartItem> getItems() {
        return items;
    }

    public Optional<CartItem> item(UUID itemId) {
        return items.stream().filter(item -> item.getId().equals(itemId)).findFirst();
    }

    public Optional<CartItem> itemFor(UUID variantId) {
        return items.stream().filter(item -> item.getVariantId().equals(variantId)).findFirst();
    }

    public void put(UUID variantId, int quantity) {
        itemFor(variantId).ifPresentOrElse(item -> item.setQuantity(quantity),
                () -> items.add(new CartItem(this, variantId, quantity)));
    }

    public void remove(CartItem item) {
        items.remove(item);
    }

    public int itemCount() {
        return items.stream().mapToInt(CartItem::getQuantity).sum();
    }

    /** Every change extends the cart's life. */
    public void touch(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
