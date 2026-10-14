package com.unispark.ecommerce.catalog.internal.persistence;

import java.util.List;

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

/** An option such as Size, with its choices. Position 1 to 3 orders options and the labels in variants. */
@Entity
@Table(name = "product_option")
public class ProductOption extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(nullable = false)
    private int position;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private LocalizedText name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<LocalizedText> choices;

    protected ProductOption() {
    }

    public ProductOption(Product product, int position, LocalizedText name, List<LocalizedText> choices) {
        this.product = product;
        this.position = position;
        update(name, choices);
    }

    public void update(LocalizedText name, List<LocalizedText> choices) {
        this.name = name;
        this.choices = List.copyOf(choices);
    }

    public int getPosition() {
        return position;
    }

    public LocalizedText getName() {
        return name;
    }

    public List<LocalizedText> getChoices() {
        return choices;
    }
}
