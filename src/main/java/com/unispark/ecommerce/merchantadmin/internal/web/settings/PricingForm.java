package com.unispark.ecommerce.merchantadmin.internal.web.settings;

import jakarta.validation.constraints.NotNull;

import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.NumberDigits;
import com.unispark.ecommerce.shared.RoundingRule;
import com.unispark.ecommerce.tenant.StoreSettings.Settings;

/** Pricing settings form backing object. */
public class PricingForm {

    @NotNull
    private Currency baseCurrency;

    @NotNull
    private Currency displayCurrency;

    @NotNull
    private RoundingRule roundingRule;

    @NotNull
    private NumberDigits numberDigits;

    static PricingForm of(Settings settings) {
        var form = new PricingForm();
        form.baseCurrency = settings.baseCurrency();
        form.displayCurrency = settings.displayCurrency();
        form.roundingRule = settings.roundingRule();
        form.numberDigits = settings.numberDigits();
        return form;
    }

    public Currency getBaseCurrency() {
        return baseCurrency;
    }

    public void setBaseCurrency(Currency baseCurrency) {
        this.baseCurrency = baseCurrency;
    }

    public Currency getDisplayCurrency() {
        return displayCurrency;
    }

    public void setDisplayCurrency(Currency displayCurrency) {
        this.displayCurrency = displayCurrency;
    }

    public RoundingRule getRoundingRule() {
        return roundingRule;
    }

    public void setRoundingRule(RoundingRule roundingRule) {
        this.roundingRule = roundingRule;
    }

    public NumberDigits getNumberDigits() {
        return numberDigits;
    }

    public void setNumberDigits(NumberDigits numberDigits) {
        this.numberDigits = numberDigits;
    }
}
