package com.unispark.ecommerce.payment.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.unispark.ecommerce.payment.PaymentMethod;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.TenantScopedEntity;

/** A store's settings for one payment method. {@code qr_image_key} is mapped once QR codes can be uploaded. */
@Entity
@Table(name = "payment_method_config")
public class PaymentMethodConfig extends TenantScopedEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PaymentMethod method;

    @Column(nullable = false)
    private boolean enabled;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "display_name")
    private LocalizedText displayName;

    @Column(name = "account_id")
    private String accountId;

    @Column(name = "account_name")
    private String accountName;

    @JdbcTypeCode(SqlTypes.JSON)
    private LocalizedText instructions;

    protected PaymentMethodConfig() {
    }

    public PaymentMethodConfig(PaymentMethod method) {
        this.method = method;
    }

    public void update(boolean enabled, LocalizedText displayName, String accountId, String accountName,
            LocalizedText instructions) {
        this.enabled = enabled;
        this.displayName = displayName;
        this.accountId = accountId;
        this.accountName = accountName;
        this.instructions = instructions;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public LocalizedText getDisplayName() {
        return displayName;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getAccountName() {
        return accountName;
    }

    public LocalizedText getInstructions() {
        return instructions;
    }
}
