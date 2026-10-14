package com.unispark.ecommerce.catalog.internal.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.TenantScopedEntity;

/** A product photo. {@code storageKey} names the media module's renditions. */
@Entity
@Table(name = "product_image")
public class ProductImage extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(name = "variant_id")
    private UUID variantId;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @JdbcTypeCode(SqlTypes.JSON)
    private LocalizedText alt;

    protected ProductImage() {
    }

    public ProductImage(Product product, String storageKey, int width, int height, int sortOrder) {
        this.product = product;
        this.storageKey = storageKey;
        this.width = width;
        this.height = height;
        this.sortOrder = sortOrder;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public LocalizedText getAlt() {
        return alt;
    }
}
