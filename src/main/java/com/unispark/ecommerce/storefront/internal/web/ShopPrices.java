package com.unispark.ecommerce.storefront.internal.web;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import com.unispark.ecommerce.pricing.PriceFormatter;
import com.unispark.ecommerce.pricing.Prices;
import com.unispark.ecommerce.shared.Money;

/**
 * Prices as shoppers see them, for templates as the bean named shopPrices: converted to the store's display
 * currency, rounded by its rule and formatted with its digits.
 */
@Component("shopPrices")
class ShopPrices {

    private final Prices prices;
    private final PriceFormatter formatter;

    ShopPrices(Prices prices, PriceFormatter formatter) {
        this.prices = prices;
        this.formatter = formatter;
    }

    public String show(Money price) {
        return price == null ? null : formatter.format(prices.display(price).amount(), LocaleContextHolder.getLocale());
    }

    /** An amount that is already in display terms, such as a cart total: formatted only. */
    public String format(Money amount) {
        return amount == null ? null : formatter.format(amount, LocaleContextHolder.getLocale());
    }
}
