package com.unispark.ecommerce.pricing;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import com.unispark.ecommerce.shared.Money;

/**
 * Formats prices for people (SPEC 7): grouped digits in the locale's numbering system (so the store's digit
 * setting, carried by the request locale, applies), decimals only when there are any, and the currency placed
 * as the message bundle says in the price.format keys.
 *
 * <p>Spring Modulith's generated docs read these comments with a JSON parser that breaks on commas inside quoted
 * text or between code tags; keep such examples out of Javadoc.
 */
@Component
public class PriceFormatter {

    private final MessageSource messages;

    PriceFormatter(MessageSource messages) {
        this.messages = messages;
    }

    /** The amount with its currency sign: 150000 pounds become 150,000 ل.س in Arabic and 150,000 SYP in English. */
    public String format(Money money, Locale locale) {
        return messages.getMessage("price.format." + money.currency().name(), new Object[] {number(money, locale)},
                locale);
    }

    /** Just the number, e.g. for a column whose header names the currency. */
    public String number(Money money, Locale locale) {
        var whole = money.amount().stripTrailingZeros().scale() <= 0;
        var format = new DecimalFormat(whole ? "#,##0" : "#,##0.00", DecimalFormatSymbols.getInstance(locale));
        return format.format(money.amount());
    }

    /** An exchange rate with as many decimals as it has (up to six), e.g. "118.375". */
    public String rate(BigDecimal rate, Locale locale) {
        return new DecimalFormat("#,##0.######", DecimalFormatSymbols.getInstance(locale)).format(rate);
    }
}
