package com.unispark.ecommerce.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void alwaysHasTwoDecimalPlaces() {
        assertThat(Money.of(new BigDecimal("5"), Currency.SYP).amount()).isEqualTo(new BigDecimal("5.00"));
        assertThat(Money.of(new BigDecimal("5.100"), Currency.USD).amount()).isEqualTo(new BigDecimal("5.10"));
    }

    @Test
    void rejectsSubCentAmountsInsteadOfRounding() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("5.005"), Currency.USD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalAmountsAreEqualWhateverTheirScale() {
        assertThat(Money.of(new BigDecimal("5"), Currency.SYP)).isEqualTo(Money.of(new BigDecimal("5.00"), Currency.SYP));
        assertThat(Money.of(new BigDecimal("5"), Currency.SYP)).isNotEqualTo(Money.of(new BigDecimal("5"), Currency.USD));
    }

    @Test
    void knowsWhetherItIsPositive() {
        assertThat(Money.zero(Currency.SYP).isPositive()).isFalse();
        assertThat(Money.of(new BigDecimal("0.01"), Currency.USD).isPositive()).isTrue();
    }

    @Test
    void addsSubtractsAndComparesInOneCurrency() {
        var thousand = Money.of(new BigDecimal("1000"), Currency.SYP);
        var fifteenHundred = Money.of(new BigDecimal("1500"), Currency.SYP);

        assertThat(thousand.plus(fifteenHundred)).isEqualTo(Money.of(new BigDecimal("2500"), Currency.SYP));
        assertThat(thousand.minus(fifteenHundred)).isEqualTo(Money.of(new BigDecimal("-500"), Currency.SYP));
        assertThat(thousand.isLessThan(fifteenHundred)).isTrue();
        assertThat(thousand.isLessThan(thousand)).isFalse();
        assertThat(thousand.sameCurrency(Money.zero(Currency.USD))).isFalse();
    }

    @Test
    void refusesToMixCurrencies() {
        var pounds = Money.of(new BigDecimal("1000"), Currency.SYP);
        var dollars = Money.of(new BigDecimal("5"), Currency.USD);

        assertThatThrownBy(() -> pounds.plus(dollars)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pounds.minus(dollars)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pounds.isLessThan(dollars)).isInstanceOf(IllegalArgumentException.class);
    }
}
