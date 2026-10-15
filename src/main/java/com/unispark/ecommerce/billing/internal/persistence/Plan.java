package com.unispark.ecommerce.billing.internal.persistence;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.unispark.ecommerce.billing.Plans.Limits;
import com.unispark.ecommerce.shared.BaseEntity;
import com.unispark.ecommerce.shared.LocalizedText;

/** A plan stores subscribe to. Platform entity. */
@Entity
@Table(name = "plan")
public class Plan extends BaseEntity {

    @Column(nullable = false, updatable = false)
    private String code;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private LocalizedText name;

    @Column(name = "monthly_price_usd", nullable = false, precision = 19, scale = 2)
    private BigDecimal monthlyPriceUsd;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Limits limits;

    @Column(nullable = false)
    private boolean active;

    protected Plan() {
    }

    public Plan(String code) {
        this.code = code;
    }

    public void update(LocalizedText name, BigDecimal monthlyPriceUsd, Limits limits, boolean active) {
        this.name = name;
        this.monthlyPriceUsd = monthlyPriceUsd;
        this.limits = limits;
        this.active = active;
    }

    public String getCode() {
        return code;
    }

    public LocalizedText getName() {
        return name;
    }

    public BigDecimal getMonthlyPriceUsd() {
        return monthlyPriceUsd;
    }

    public Limits getLimits() {
        return limits == null ? Limits.NONE : limits;
    }

    public boolean isActive() {
        return active;
    }
}
