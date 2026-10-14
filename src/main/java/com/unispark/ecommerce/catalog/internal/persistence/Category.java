package com.unispark.ecommerce.catalog.internal.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.TenantScopedEntity;

@Entity
@Table(name = "category")
public class Category extends TenantScopedEntity {

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private LocalizedText name;

    @Column(nullable = false)
    private String slug;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active;

    protected Category() {
    }

    public Category(LocalizedText name, String slug, UUID parentId, int sortOrder) {
        update(name, slug, parentId, sortOrder);
        this.active = true;
    }

    public void update(LocalizedText name, String slug, UUID parentId, int sortOrder) {
        this.name = name;
        this.slug = slug;
        this.parentId = parentId;
        this.sortOrder = sortOrder;
    }

    public LocalizedText getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public UUID getParentId() {
        return parentId;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
