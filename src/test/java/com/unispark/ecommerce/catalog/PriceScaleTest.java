package com.unispark.ecommerce.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.unispark.ecommerce.catalog.StorefrontCatalog.PriceScale;
import com.unispark.ecommerce.shared.Currency;

class PriceScaleTest {

    @Test
    void scalesDollarsUpWhenDisplayingPounds() {
        assertThat(PriceScale.forDisplay(Currency.SYP, new BigDecimal("118.5")))
                .isEqualTo(new PriceScale(Currency.USD, new BigDecimal("118.5")));
    }

    @Test
    void scalesPoundsDownWhenDisplayingDollars() {
        var scale = PriceScale.forDisplay(Currency.USD, new BigDecimal("125"));

        assertThat(scale.foreign()).isEqualTo(Currency.SYP);
        assertThat(scale.factor()).isEqualByComparingTo("0.008");
    }

    @Test
    void comparesRawAmountsWithoutARate() {
        assertThat(PriceScale.forDisplay(Currency.SYP, null)).isSameAs(PriceScale.NONE);
    }
}
