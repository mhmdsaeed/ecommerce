package com.unispark.ecommerce.customer.internal.persistence;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.unispark.ecommerce.delivery.Governorate;
import com.unispark.ecommerce.shared.TenantScopedEntity;

@Entity
@Table(name = "customer_address")
public class CustomerAddress extends TenantScopedEntity {

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Governorate governorate;

    @Column(nullable = false)
    private String city;

    private String area;

    private String landmark;

    private String details;

    private String phone;

    @Column(precision = 9, scale = 6)
    private BigDecimal lat;

    @Column(precision = 9, scale = 6)
    private BigDecimal lng;

    protected CustomerAddress() {
    }

    public CustomerAddress(UUID customerId) {
        this.customerId = customerId;
    }

    public void update(Governorate governorate, String city, String area, String landmark, String details,
            String phone, BigDecimal lat, BigDecimal lng) {
        this.governorate = governorate;
        this.city = city;
        this.area = area;
        this.landmark = landmark;
        this.details = details;
        this.phone = phone;
        this.lat = lat;
        this.lng = lng;
    }

    public UUID getCustomerId() {
        return customerId;
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

    public String getLandmark() {
        return landmark;
    }

    public String getDetails() {
        return details;
    }

    public String getPhone() {
        return phone;
    }

    public BigDecimal getLat() {
        return lat;
    }

    public BigDecimal getLng() {
        return lng;
    }
}
