package com.unispark.ecommerce.order.internal.persistence;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.unispark.ecommerce.order.Orders.Option;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.TenantScopedEntity;

/** One order line, with the product as it was when ordered. Prices are in the order's currency. */
@Entity
@Table(name = "order_item")
public class OrderItem extends TenantScopedEntity {

    /** The product part of the line, kept as ordered so catalog changes never alter past orders. */
    public record ProductSnapshot(LocalizedText name, String sku, List<Option> options) {
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private ShopOrder order;

    @Column(nullable = false, updatable = false)
    private int position;

    @Column(name = "variant_id", updatable = false)
    private UUID variantId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private ProductSnapshot product;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal unitPrice;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "line_total", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal lineTotal;

    protected OrderItem() {
    }

    public OrderItem(UUID variantId, ProductSnapshot product, BigDecimal unitPrice, int quantity) {
        this.variantId = variantId;
        this.product = product;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    void attachTo(ShopOrder order, int position) {
        this.order = order;
        this.position = position;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public ProductSnapshot getProduct() {
        return product;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }
}
