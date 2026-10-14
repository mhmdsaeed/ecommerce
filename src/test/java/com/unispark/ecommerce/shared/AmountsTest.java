package com.unispark.ecommerce.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AmountsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "150000|150000.00", "150,000|150000.00", "150 000|150000.00", "1,250,000|1250000.00",
            "١٥٠٬٠٠٠|150000.00", "١٥٠٠٠٠|150000.00", "12.5|12.50", "12.50|12.50", "١٢٫٥|12.50", "0|0.00",
            "  42  |42.00", "1,000.75|1000.75"})
    void parsesAmountsAsMerchantsTypeThem(String input, String expected) {
        assertThat(Amounts.parse(input)).contains(new BigDecimal(expected));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"12,50", "1,2345", "-5", "12.345", "abc", "1e5", "12..5", ",500", "99999999999999"})
    void rejectsAnythingAmbiguousOrInvalid(String input) {
        assertThat(Amounts.parse(input)).isEmpty();
    }
}
