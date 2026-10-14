package com.unispark.ecommerce.pricing.internal;

import org.springframework.stereotype.Service;

import com.unispark.ecommerce.pricing.CurrencyConversion;
import com.unispark.ecommerce.pricing.ExchangeRates;
import com.unispark.ecommerce.pricing.Prices;
import com.unispark.ecommerce.shared.Money;
import com.unispark.ecommerce.tenant.StoreSettings;

@Service
class PriceService implements Prices {

    private final StoreSettings settings;
    private final ExchangeRates rates;

    PriceService(StoreSettings settings, ExchangeRates rates) {
        this.settings = settings;
        this.rates = rates;
    }

    @Override
    public DisplayPrice display(Money price) {
        var store = settings.current();
        if (price.currency() == store.displayCurrency()) {
            return new DisplayPrice(price, price, null);
        }
        return rates.current()
                .map(rate -> new DisplayPrice(
                        CurrencyConversion.convert(price, store.displayCurrency(), rate.sypPerUsd(), store.roundingRule()),
                        price, rate.sypPerUsd()))
                .orElseGet(() -> new DisplayPrice(price, price, null));
    }
}
