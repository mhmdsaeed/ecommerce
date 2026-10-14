package com.unispark.ecommerce.merchantadmin.internal.web.catalog;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import com.unispark.ecommerce.pricing.PriceFormatter;
import com.unispark.ecommerce.shared.Money;

/**
 * Prices in admin templates, used as the bean named money. Formats with the request locale, which carries the
 * store's digit setting.
 *
 * <p>Javadoc here ends up in Spring Modulith's generated docs, whose JSON reader trips over braces combined with
 * quoted commas; keep such examples out of comments in this package.
 */
@Component("money")
class MoneyText {

    private final PriceFormatter formatter;

    MoneyText(PriceFormatter formatter) {
        this.formatter = formatter;
    }

    /** Amount with its currency, as the bundle places it. */
    public String format(Money money) {
        return money == null ? "" : formatter.format(money, LocaleContextHolder.getLocale());
    }

    /** Amount only. */
    public String amount(Money money) {
        return money == null ? "" : formatter.number(money, LocaleContextHolder.getLocale());
    }
}
