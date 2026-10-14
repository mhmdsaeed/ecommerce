package com.unispark.ecommerce.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ArabicTextTest {

    @Test
    void foldsSpellingVariants() {
        assertThat(ArabicText.fold("أحمر")).isEqualTo(ArabicText.fold("احمر"));
        assertThat(ArabicText.fold("إدلب")).isEqualTo("ادلب");
        assertThat(ArabicText.fold("المزة")).isEqualTo("المزه");
        assertThat(ArabicText.fold("مستشفى")).isEqualTo("مستشفي");
        assertThat(ArabicText.fold("دِمَشْق")).isEqualTo("دمشق");
        assertThat(ArabicText.fold("الـمـزة")).isEqualTo("المزه");
    }

    @Test
    void lowercasesAndCollapsesSpaces() {
        assertThat(ArabicText.fold("  Old \t Town ")).isEqualTo("old town");
        assertThat(ArabicText.fold(null)).isEmpty();
    }
}
