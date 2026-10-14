package com.unispark.ecommerce.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class StoreSlugsTest {

    @ParameterizedTest
    @ValueSource(strings = {"abc", "demo1", "rose-shop", "a1b", "a2345678901234567890123456789012"})
    void acceptsLowercaseDnsLabelsOf3To32Characters(String slug) {
        assertThat(StoreSlugs.hasValidFormat(slug)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "Demo", "rose_shop", "-shop", "shop-", "my shop", "متجر",
            "a23456789012345678901234567890123"})
    void rejectsAnythingElse(String slug) {
        assertThat(StoreSlugs.hasValidFormat(slug)).isFalse();
    }

    @Test
    void normalizesWhatMerchantsType() {
        assertThat(StoreSlugs.normalize("  Rose-Shop ")).isEqualTo("rose-shop");
        assertThat(StoreSlugs.normalize(null)).isEmpty();
    }

    @Test
    void reservesPlatformSubdomains() {
        assertThat(StoreSlugs.isReserved("admin")).isTrue();
        assertThat(StoreSlugs.isReserved("www")).isTrue();
        assertThat(StoreSlugs.isReserved("demo1")).isFalse();
    }
}
