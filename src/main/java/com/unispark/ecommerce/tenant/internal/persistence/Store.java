package com.unispark.ecommerce.tenant.internal.persistence;

import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.unispark.ecommerce.shared.BaseEntity;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.Ids;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.tenant.StoreStatus;
import com.unispark.ecommerce.tenant.StoreSummary;

/**
 * A merchant's store; its id is the tenant id. Platform entity, so it does not extend {@code TenantScopedEntity}.
 * {@code plan_code} and {@code theme} are mapped when billing (M12) and theming (M5) need them.
 */
@Entity
@Table(name = "store")
public class Store extends BaseEntity {

    @Column(nullable = false, updatable = false)
    private String slug;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private LocalizedText name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StoreStatus status;

    @Column(name = "default_locale", nullable = false)
    private String defaultLocale;

    @Enumerated(EnumType.STRING)
    @Column(name = "base_currency", nullable = false)
    private Currency baseCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "display_currency", nullable = false)
    private Currency displayCurrency;

    @Column(name = "contact_phone")
    private String contactPhone;

    private String whatsapp;

    @Column(name = "created_by")
    private UUID createdBy;

    protected Store() {
    }

    public Store(String slug, LocalizedText name, StoreStatus status) {
        this(Ids.newId(), slug, name, status, Locale.forLanguageTag("ar"));
    }

    public Store(UUID id, String slug, LocalizedText name, StoreStatus status, Locale defaultLocale) {
        super(id);
        this.slug = slug;
        this.name = name;
        this.status = status;
        this.defaultLocale = "en".equals(defaultLocale.getLanguage()) ? "en" : "ar";
        this.baseCurrency = Currency.SYP;
        this.displayCurrency = Currency.SYP;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public void recordCreator(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public StoreSummary toSummary() {
        return new StoreSummary(getId(), slug, name, status, Locale.forLanguageTag(defaultLocale));
    }

    public String getSlug() {
        return slug;
    }

    public StoreStatus getStatus() {
        return status;
    }
}
