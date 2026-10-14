package com.unispark.ecommerce.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SlugsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Cotton Shirt|cotton-shirt",
            "  Café  Crème!! |cafe-creme",
            "قميص قطني|قميص-قطني",
            "قَمِيصٌ قُطْنِيّ|قميص-قطني",
            "شـــاي أخضر|شاي-اخضر",
            "T-Shirt (XL) / 2024|t-shirt-xl-2024"})
    void makesReadableSlugsFromNamesInAnyScript(String name, String slug) {
        assertThat(Slugs.fromName(name, "fallback")).isEqualTo(slug);
        assertThat(Slugs.isValid(slug)).isTrue();
    }

    @Test
    void fallsBackWhenTheNameHasNoLettersOrDigits() {
        assertThat(Slugs.fromName("!!! ???", "product")).isEqualTo("product");
    }

    @Test
    void cutsLongNamesAtTheLimit() {
        var slug = Slugs.fromName("word ".repeat(40), "x");

        assertThat(slug.length()).isLessThanOrEqualTo(Slugs.MAX_LENGTH);
        assertThat(slug).doesNotEndWith("-");
        assertThat(Slugs.isValid(slug)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"has space", "Upper", "trailing-", "-leading", "double--hyphen", "under_score", "a/b", ""})
    void rejectsInvalidSlugs(String slug) {
        assertThat(Slugs.isValid(slug)).isFalse();
    }

    @Test
    void numbersDuplicates() {
        var taken = Set.of("shirt", "shirt-2");

        assertThat(Slugs.unique("shirt", taken::contains)).isEqualTo("shirt-3");
        assertThat(Slugs.unique("hat", taken::contains)).isEqualTo("hat");
    }
}
