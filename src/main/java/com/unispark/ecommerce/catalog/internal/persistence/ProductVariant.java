package com.unispark.ecommerce.catalog.internal.persistence;

import java.math.BigDecimal;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.unispark.ecommerce.shared.Money;
import com.unispark.ecommerce.shared.TenantScopedEntity;

/** One sellable combination of option values. Prices are in the product's currency. */
@Entity
@Table(name = "product_variant")
public class ProductVariant extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(nullable = false)
    private int position;

    private String sku;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "option_values", nullable = false)
    private List<String> optionValues;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Column(name = "compare_at_price", precision = 19, scale = 2)
    private BigDecimal compareAtPrice;

    @Column(nullable = false)
    private boolean active;

    protected ProductVariant() {
    }

    public ProductVariant(Product product, int position, List<String> optionValues, BigDecimal price) {
        this.product = product;
        this.position = position;
        this.optionValues = List.copyOf(optionValues);
        this.price = price;
        this.active = true;
    }

    public void update(String sku, BigDecimal price, BigDecimal compareAtPrice, boolean active) {
        this.sku = sku;
        this.price = price;
        this.compareAtPrice = compareAtPrice;
        this.active = active;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public Product getProduct() {
        return product;
    }

    public int getPosition() {
        return position;
    }

    public String getSku() {
        return sku;
    }

    public List<String> getOptionValues() {
        return optionValues;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public BigDecimal getCompareAtPrice() {
        return compareAtPrice;
    }

    public boolean isActive() {
        return active;
    }

    public Money price() {
        return Money.of(price, product.getPriceCurrency());
    }

    public Money compareAtPrice() {
        return compareAtPrice == null ? null : Money.of(compareAtPrice, product.getPriceCurrency());
    }
}
