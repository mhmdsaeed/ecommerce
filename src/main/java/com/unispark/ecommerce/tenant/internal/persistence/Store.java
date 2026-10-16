package com.unispark.ecommerce.tenant.internal.persistence;

import java.math.BigDecimal;
import java.time.Instant;
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
import com.unispark.ecommerce.shared.NumberDigits;
import com.unispark.ecommerce.shared.RoundingRule;
import com.unispark.ecommerce.shared.SecretTokens;
import com.unispark.ecommerce.tenant.StoreSettings;
import com.unispark.ecommerce.tenant.StoreStatus;
import com.unispark.ecommerce.tenant.StoreSummary;
import com.unispark.ecommerce.tenant.StoreTheme;
import com.unispark.ecommerce.tenant.SuspensionKind;

/**
 * A merchant's store; its id is the tenant id. Platform entity, so it does not extend {@code TenantScopedEntity}.
 * The plan lives in billing's subscription.
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

    @Enumerated(EnumType.STRING)
    @Column(name = "rounding_rule", nullable = false)
    private RoundingRule roundingRule;

    @Enumerated(EnumType.STRING)
    @Column(name = "number_digits", nullable = false)
    private NumberDigits numberDigits;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private StoreTheme theme;

    @Column(name = "otp_required", nullable = false)
    private boolean otpRequired;

    @Column(name = "new_customer_max_total", precision = 19, scale = 2)
    private BigDecimal newCustomerMaxTotal;

    @Column(name = "contact_phone")
    private String contactPhone;

    private String whatsapp;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "screened_at")
    private Instant screenedAt;

    @Column(name = "screened_by")
    private String screenedBy;

    @Column(name = "screening_notes")
    private String screeningNotes;

    @Enumerated(EnumType.STRING)
    @Column(name = "suspension_kind")
    private SuspensionKind suspensionKind;

    @Column(name = "suspension_reason")
    private String suspensionReason;

    @Column(name = "launched_at")
    private Instant launchedAt;

    @Column(name = "preview_token", nullable = false, updatable = false)
    private String previewToken = SecretTokens.newToken();

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
        this.roundingRule = RoundingRule.NEAREST_5;
        this.numberDigits = NumberDigits.WESTERN;
        this.theme = StoreTheme.DEFAULT;
    }

    public void updatePricing(Currency baseCurrency, Currency displayCurrency, RoundingRule roundingRule,
            NumberDigits numberDigits) {
        this.baseCurrency = baseCurrency;
        this.displayCurrency = displayCurrency;
        this.roundingRule = roundingRule;
        this.numberDigits = numberDigits;
    }

    public StoreSettings.Settings toSettings() {
        return new StoreSettings.Settings(getId(), name, Locale.forLanguageTag(defaultLocale), baseCurrency,
                displayCurrency, roundingRule, numberDigits, theme == null ? StoreTheme.DEFAULT : theme, otpRequired,
                newCustomerMaxTotal, contactPhone, whatsapp, launchedAt);
    }

    public void updateCheckout(boolean otpRequired, BigDecimal newCustomerMaxTotal) {
        this.otpRequired = otpRequired;
        this.newCustomerMaxTotal = newCustomerMaxTotal;
    }

    public void updateTheme(StoreTheme theme) {
        this.theme = theme == null ? StoreTheme.DEFAULT : theme;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public void recordCreator(UUID createdBy) {
        this.createdBy = createdBy;
    }

    /** Opens the store to shoppers; launching again keeps the first date. */
    public void launch(Instant at) {
        if (launchedAt == null) {
            launchedAt = at;
        }
    }

    public void updateDetails(LocalizedText name, String contactPhone, String whatsapp) {
        this.name = name;
        this.contactPhone = contactPhone;
        this.whatsapp = whatsapp;
    }

    public Instant getLaunchedAt() {
        return launchedAt;
    }

    public String getPreviewToken() {
        return previewToken;
    }

    public void suspend(SuspensionKind kind, String reason) {
        status = StoreStatus.SUSPENDED;
        suspensionKind = kind;
        suspensionReason = reason;
    }

    /** Open again: ACTIVE once screened, otherwise back to TRIAL. */
    public void reopen() {
        status = screenedAt != null ? StoreStatus.ACTIVE : StoreStatus.TRIAL;
        suspensionKind = null;
        suspensionReason = null;
    }

    public void screened(Instant at, String by, String notes) {
        screenedAt = at;
        screenedBy = by;
        screeningNotes = notes;
    }

    /** A suspended store with no recorded kind predates billing and counts as suspended by hand. */
    public SuspensionKind getSuspensionKind() {
        if (status != StoreStatus.SUSPENDED) {
            return null;
        }
        return suspensionKind != null ? suspensionKind : SuspensionKind.MANUAL;
    }

    public String getSuspensionReason() {
        return suspensionReason;
    }

    public Instant getScreenedAt() {
        return screenedAt;
    }

    public String getScreenedBy() {
        return screenedBy;
    }

    public String getScreeningNotes() {
        return screeningNotes;
    }

    public LocalizedText getName() {
        return name;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public StoreSummary toSummary() {
        return new StoreSummary(getId(), slug, name, status, Locale.forLanguageTag(defaultLocale),
                launchedAt != null);
    }

    public String getSlug() {
        return slug;
    }

    public StoreStatus getStatus() {
        return status;
    }
}
