package com.unispark.ecommerce.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ListParamsTest {

    @Test
    void readsPageNumbersAndFallsBackToTheFirstPage() {
        assertThat(ListParams.page("3")).isEqualTo(3);
        assertThat(ListParams.page(" 2 ")).isEqualTo(2);
        assertThat(ListParams.page(null)).isZero();
        assertThat(ListParams.page("abc")).isZero();
        assertThat(ListParams.page("-4")).isZero();
        assertThat(ListParams.page("99999999999")).isZero();
    }

    @Test
    void readsKnownConstantsAndIgnoresTheRest() {
        assertThat(ListParams.constant(Currency.class, "USD")).contains(Currency.USD);
        assertThat(ListParams.constant(Currency.class, "usd")).isEmpty();
        assertThat(ListParams.constant(Currency.class, "EUR")).isEmpty();
        assertThat(ListParams.constant(Currency.class, "")).isEmpty();
        assertThat(ListParams.constant(Currency.class, null)).isEmpty();
    }
}
