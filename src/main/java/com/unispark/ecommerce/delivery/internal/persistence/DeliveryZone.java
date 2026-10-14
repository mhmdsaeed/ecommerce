package com.unispark.ecommerce.delivery.internal.persistence;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.unispark.ecommerce.delivery.Destination;
import com.unispark.ecommerce.delivery.Governorate;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.TenantScopedEntity;

/** A delivery zone. The name keys are derived from the names here, so they never disagree. */
@Entity
@Table(name = "delivery_zone")
public class DeliveryZone extends TenantScopedEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Governorate governorate;

    private String city;

    @Column(name = "city_key")
    private String cityKey;

    private String area;

    @Column(name = "area_key")
    private String areaKey;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal fee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Currency currency;

    @Column(name = "min_order", precision = 19, scale = 2)
    private BigDecimal minOrder;

    @Column(name = "free_over", precision = 19, scale = 2)
    private BigDecimal freeOver;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "eta_text")
    private LocalizedText eta;

    @Column(nullable = false)
    private boolean active;

    protected DeliveryZone() {
    }

    public DeliveryZone(Destination destination, Currency currency, BigDecimal fee, BigDecimal minOrder,
            BigDecimal freeOver, LocalizedText eta) {
        update(destination, currency, fee, minOrder, freeOver, eta);
        this.active = true;
    }

    public void update(Destination destination, Currency currency, BigDecimal fee, BigDecimal minOrder,
            BigDecimal freeOver, LocalizedText eta) {
        this.governorate = destination.governorate();
        this.city = destination.city();
        this.cityKey = destination.cityKey();
        this.area = destination.area();
        this.areaKey = destination.areaKey();
        this.currency = currency;
        this.fee = fee;
        this.minOrder = minOrder;
        this.freeOver = freeOver;
        this.eta = eta;
    }

    public Destination destination() {
        return Destination.of(governorate, city, area);
    }

    public Governorate getGovernorate() {
        return governorate;
    }

    public String getCity() {
        return city;
    }

    public String getArea() {
        return area;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public Currency getCurrency() {
        return currency;
    }

    public BigDecimal getMinOrder() {
        return minOrder;
    }

    public BigDecimal getFreeOver() {
        return freeOver;
    }

    public LocalizedText getEta() {
        return eta;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
