package com.unispark.ecommerce.shared.internal.web;

import java.util.Locale;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Plural forms in templates, used as the bean named plurals: the CLDR plural category of a count in the request
 * language, to pick a message such as {@code category.product-count.few}. Message formats cannot express Arabic's
 * rule, which looks at the last two digits: 3 to 10 take the plural (5 منتجات, 105 منتجات), 11 to 99 the accusative
 * singular (11 منتجاً, 250 منتجاً), and round hundreds the genitive singular (100 منتج). Each counted message has a key
 * for every category in both bundles.
 */
@Component("plurals")
class PluralText {

    /** zero, one, two, few, many or other. */
    public String of(long count) {
        return of(count, LocaleContextHolder.getLocale());
    }

    static String of(long count, Locale locale) {
        var n = Math.abs(count);
        if (!"ar".equals(locale.getLanguage())) {
            return n == 0 ? "zero" : n == 1 ? "one" : "other";
        }
        var lastTwo = n % 100;
        if (n == 0) {
            return "zero";
        }
        if (n == 1) {
            return "one";
        }
        if (n == 2) {
            return "two";
        }
        if (lastTwo >= 3 && lastTwo <= 10) {
            return "few";
        }
        if (lastTwo >= 11) {
            return "many";
        }
        return "other";
    }
}
