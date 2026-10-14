package com.unispark.ecommerce.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.MessageFormat;
import java.util.Locale;

import org.junit.jupiter.api.Test;

class NumberDigitsTest {

    @Test
    void changesOnlyTheNumberingSystemOfTheLocale() {
        var arabic = Locale.forLanguageTag("ar");

        var western = NumberDigits.WESTERN.apply(arabic);
        var eastern = NumberDigits.ARABIC_INDIC.apply(arabic);

        assertThat(western.getLanguage()).isEqualTo("ar");
        assertThat(western.toLanguageTag()).isEqualTo("ar-u-nu-latn");
        assertThat(eastern.toLanguageTag()).isEqualTo("ar-u-nu-arab");
    }

    @Test
    void appliesToMessageFormatArguments() {
        var arabic = Locale.forLanguageTag("ar");

        assertThat(new MessageFormat("{0} / {1}", NumberDigits.WESTERN.apply(arabic)).format(new Object[] {2, 10}))
                .isEqualTo("2 / 10");
        assertThat(new MessageFormat("{0} / {1}", NumberDigits.ARABIC_INDIC.apply(arabic)).format(new Object[] {2, 10}))
                .isEqualTo("٢ / ١٠");
    }

    @Test
    void replacesAnEarlierChoice() {
        var once = NumberDigits.ARABIC_INDIC.apply(Locale.ENGLISH);

        assertThat(NumberDigits.WESTERN.apply(once).toLanguageTag()).isEqualTo("en-u-nu-latn");
    }
}
