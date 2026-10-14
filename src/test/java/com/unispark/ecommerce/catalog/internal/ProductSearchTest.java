package com.unispark.ecommerce.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProductSearchTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "أحمر|احمر", "إبريق|ابريق", "آلة|اله", "مدرسة|مدرسه", "مستشفى|مستشفي",
            "قَمِيص|قميص", "شـــاي|شاي", "  Blue   Teapot |blue teapot", "ٱلبيت|البيت"})
    void normalizesArabicSpellingVariantsLikeTheSearchColumn(String query, String normalized) {
        assertThat(ProductSearch.normalize(query)).isEqualTo(normalized);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"100%|%100\\%%", "a_b|%a\\_b%", "back\\slash|%back\\\\slash%"})
    void escapesLikeWildcardsInQueries(String query, String pattern) {
        assertThat(ProductSearch.containsPattern(query)).isEqualTo(pattern);
    }
}
