package com.unispark.ecommerce.merchantadmin.internal.web.catalog;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.unispark.ecommerce.shared.Money;

/**
 * Admin display of amounts, used from templates as the bean named money: 150,000 or 12.50, Western digits for now.
 * Store-specific formatting (digits, rounding, display currency) arrives with pricing (M4, SPEC 7).
 *
 * <p>Javadoc here ends up in Spring Modulith's generated docs, whose JSON reader trips over braces combined with
 * quoted commas; keep such examples out of comments in this package.
 */
@Component("money")
class MoneyText {

    public String amount(Money money) {
        if (money == null) {
            return "";
        }
        var whole = money.amount().stripTrailingZeros().scale() <= 0;
        var format = new DecimalFormat(whole ? "#,##0" : "#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
        return format.format(money.amount());
    }
}
