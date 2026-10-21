package com.unispark.ecommerce.shared.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.Test;

class PluralTextTest {

    private static final Locale ARABIC = Locale.forLanguageTag("ar");

    @Test
    void picksArabicFormsByTheLastTwoDigits() {
        assertThat(PluralText.of(0, ARABIC)).isEqualTo("zero");
        assertThat(PluralText.of(1, ARABIC)).isEqualTo("one");
        assertThat(PluralText.of(2, ARABIC)).isEqualTo("two");
        assertThat(PluralText.of(3, ARABIC)).isEqualTo("few");
        assertThat(PluralText.of(105, ARABIC)).isEqualTo("few");
        assertThat(PluralText.of(11, ARABIC)).isEqualTo("many");
        assertThat(PluralText.of(250, ARABIC)).isEqualTo("many");
        assertThat(PluralText.of(100, ARABIC)).isEqualTo("other");
        assertThat(PluralText.of(1000, ARABIC)).isEqualTo("other");
        assertThat(PluralText.of(102, ARABIC)).isEqualTo("other");
    }

    @Test
    void usesOneAndOtherInEnglish() {
        assertThat(PluralText.of(0, Locale.ENGLISH)).isEqualTo("zero");
        assertThat(PluralText.of(1, Locale.ENGLISH)).isEqualTo("one");
        assertThat(PluralText.of(5, Locale.ENGLISH)).isEqualTo("other");
    }
}
