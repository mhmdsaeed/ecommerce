package com.unispark.ecommerce.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.Money;
import com.unispark.ecommerce.shared.RoundingRule;

/** M4 done-when: conversion and rounding (SPEC 7, 17). */
class CurrencyConversionTest {

    @ParameterizedTest(name = "${0} at {1} with {2} = {3} SYP")
    @CsvSource({
            // dollars, rate, rule, pounds
            "4.50,   118.5, NONE,        533.25",
            "4.50,   118.5, NEAREST_5,   535.00",
            "4.50,   118.5, NEAREST_10,  530.00",
            "4.50,   118.5, NEAREST_100, 500.00",
            "1,      118.5, NEAREST_5,   120.00",
            "1,      118.5, NEAREST_10,  120.00",
            "25,     118.5, NEAREST_100, 3000.00",
            "0.33,   118.5, NONE,        39.11",
            "1.01,   118.5, NONE,        119.69",
            "12.34,  118.375, NONE,      1460.75"})
    void convertsDollarsToPoundsWithTheStoreRoundingRule(String dollars, String rate, RoundingRule rule, String pounds) {
        var converted = CurrencyConversion.convert(usd(dollars), Currency.SYP, new BigDecimal(rate), rule);

        assertThat(converted).isEqualTo(syp(pounds));
    }

    @ParameterizedTest(name = "{0} rounded {1} = {2}")
    @CsvSource({
            "112.49, NEAREST_5,   110.00",
            "112.50, NEAREST_5,   115.00",
            "114.99, NEAREST_10,  110.00",
            "115.00, NEAREST_10,  120.00",
            "149.99, NEAREST_100, 100.00",
            "150.00, NEAREST_100, 200.00",
            "0.005,  NONE,        0.01",
            "0.004,  NONE,        0.00"})
    void roundsToTheNearestStepHalfUp(String amount, RoundingRule rule, String expected) {
        assertThat(rule.apply(new BigDecimal(amount))).isEqualTo(new BigDecimal(expected));
    }

    @Test
    void neverRoundsAPositivePriceDownToZero() {
        assertThat(CurrencyConversion.convert(usd("0.10"), Currency.SYP, new BigDecimal("118.5"), RoundingRule.NEAREST_100))
                .isEqualTo(syp("100"));
        assertThat(CurrencyConversion.convert(usd("0.01"), Currency.SYP, new BigDecimal("118.5"), RoundingRule.NEAREST_5))
                .isEqualTo(syp("5"));
        assertThat(CurrencyConversion.convert(syp("0.50"), Currency.USD, new BigDecimal("118.5"), RoundingRule.NEAREST_5))
                .isEqualTo(usd("0.01"));
    }

    @Test
    void keepsAFreeItemFree() {
        assertThat(CurrencyConversion.convert(usd("0"), Currency.SYP, new BigDecimal("118.5"), RoundingRule.NEAREST_100))
                .isEqualTo(syp("0"));
        assertThat(CurrencyConversion.convert(syp("0"), Currency.USD, new BigDecimal("118.5"), RoundingRule.NONE))
                .isEqualTo(usd("0"));
    }

    @ParameterizedTest(name = "{0} SYP at {1} = ${2}")
    @CsvSource({
            "1000,   118.5, 8.44",
            "15000,  118.5, 126.58",
            "118.5,  118.5, 1.00",
            "59.25,  118.5, 0.50",
            "177.75, 118.5, 1.50"})
    void convertsPoundsToDollarsRoundingHalfUpToCents(String pounds, String rate, String dollars) {
        // The pound rounding rule never applies to dollars.
        var converted = CurrencyConversion.convert(syp(pounds), Currency.USD, new BigDecimal(rate), RoundingRule.NEAREST_100);

        assertThat(converted).isEqualTo(usd(dollars));
    }

    @Test
    void leavesPricesAlreadyInTheTargetCurrencyExactlyAsTyped() {
        assertThat(CurrencyConversion.convert(syp("1234.56"), Currency.SYP, new BigDecimal("118.5"), RoundingRule.NEAREST_100))
                .isEqualTo(syp("1234.56"));
        assertThat(CurrencyConversion.convert(usd("4.99"), Currency.USD, null, RoundingRule.NEAREST_5)).isEqualTo(usd("4.99"));
    }

    @Test
    void handlesLargeAmountsWithoutLosingPrecision() {
        assertThat(CurrencyConversion.convert(usd("99999999.99"), Currency.SYP, new BigDecimal("118.123456"), RoundingRule.NONE))
                .isEqualTo(syp("11812345598.82"));
    }

    @Test
    void refusesMissingOrNonPositiveRates() {
        assertThatThrownBy(() -> CurrencyConversion.convert(usd("1"), Currency.SYP, BigDecimal.ZERO, RoundingRule.NONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CurrencyConversion.convert(usd("1"), Currency.SYP, new BigDecimal("-5"), RoundingRule.NONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CurrencyConversion.convert(usd("1"), Currency.SYP, null, RoundingRule.NONE))
                .isInstanceOf(NullPointerException.class);
    }

    private static Money usd(String amount) {
        return Money.of(new BigDecimal(amount).setScale(2, RoundingMode.HALF_UP), Currency.USD);
    }

    private static Money syp(String amount) {
        return Money.of(new BigDecimal(amount).setScale(2, RoundingMode.HALF_UP), Currency.SYP);
    }
}
