package com.unispark.ecommerce.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.Money;
import com.unispark.ecommerce.shared.NumberDigits;

class PriceFormatterTest {

    static final Locale ARABIC = Locale.forLanguageTag("ar");

    final PriceFormatter formatter = new PriceFormatter(messages());

    @Test
    void formatsWithWesternDigitsWhenTheStoreChoosesThem() {
        var locale = NumberDigits.WESTERN.apply(ARABIC);

        assertThat(formatter.format(syp("150000"), locale)).isEqualTo("150,000 ل.س");
        assertThat(formatter.format(usd("12.5"), locale)).isEqualTo("12.50 $");
    }

    @Test
    void formatsWithArabicIndicDigitsAndSeparatorsWhenTheStoreChoosesThem() {
        var locale = NumberDigits.ARABIC_INDIC.apply(ARABIC);

        assertThat(formatter.format(syp("150000"), locale)).isEqualTo("١٥٠٬٠٠٠ ل.س");
        assertThat(formatter.format(usd("12.5"), locale)).isEqualTo("١٢٫٥٠ $");
    }

    @Test
    void formatsEnglishPrices() {
        assertThat(formatter.format(syp("150000"), Locale.ENGLISH)).isEqualTo("150,000 SYP");
        assertThat(formatter.format(usd("4.5"), Locale.ENGLISH)).isEqualTo("$4.50");
        assertThat(formatter.format(usd("25"), Locale.ENGLISH)).isEqualTo("$25");
    }

    @Test
    void showsDecimalsOnlyWhenThereAreAny() {
        assertThat(formatter.number(syp("1500.00"), Locale.ENGLISH)).isEqualTo("1,500");
        assertThat(formatter.number(syp("1500.05"), Locale.ENGLISH)).isEqualTo("1,500.05");
    }

    @Test
    void formatsRatesWithTheirOwnDecimals() {
        assertThat(formatter.rate(new BigDecimal("118.375"), Locale.ENGLISH)).isEqualTo("118.375");
        assertThat(formatter.rate(new BigDecimal("12500"), Locale.ENGLISH)).isEqualTo("12,500");
        assertThat(formatter.rate(new BigDecimal("118.5"), NumberDigits.ARABIC_INDIC.apply(ARABIC))).isEqualTo("١١٨٫٥");
    }

    private static ResourceBundleMessageSource messages() {
        var messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messages.setFallbackToSystemLocale(false);
        return messages;
    }

    private static Money syp(String amount) {
        return Money.of(new BigDecimal(amount), Currency.SYP);
    }

    private static Money usd(String amount) {
        return Money.of(new BigDecimal(amount), Currency.USD);
    }
}
