package com.unispark.ecommerce.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;

import org.junit.jupiter.api.Test;

class LocalizedTextTest {

    static final Locale ARABIC = Locale.forLanguageTag("ar");

    @Test
    void returnsTheRequestedLanguage() {
        var text = LocalizedText.of("قهوة", "Coffee");

        assertThat(text.in(ARABIC)).isEqualTo("قهوة");
        assertThat(text.in(Locale.ENGLISH)).isEqualTo("Coffee");
    }

    @Test
    void fallsBackToTheOtherLanguage() {
        assertThat(LocalizedText.of("قهوة", null).in(Locale.ENGLISH)).isEqualTo("قهوة");
        assertThat(LocalizedText.of(" ", "Coffee").in(ARABIC)).isEqualTo("Coffee");
    }

    @Test
    void prefersArabicForOtherLocales() {
        assertThat(LocalizedText.of("قهوة", "Coffee").in(Locale.FRENCH)).isEqualTo("قهوة");
        assertThat(LocalizedText.of("قهوة", "Coffee").in(null)).isEqualTo("قهوة");
    }

    @Test
    void rejectsTextWithNoLanguage() {
        assertThatThrownBy(() -> LocalizedText.of(null, "  ")).isInstanceOf(IllegalArgumentException.class);
    }
}
