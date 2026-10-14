package com.unispark.ecommerce.cart.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;

import org.junit.jupiter.api.Test;

class CartTokensTest {

    @Test
    void createsUniqueWellFormedTokens() {
        var tokens = new HashSet<String>();
        for (var i = 0; i < 1000; i++) {
            var token = CartTokens.newToken();
            assertThat(CartTokens.isWellFormed(token)).isTrue();
            tokens.add(token);
        }
        assertThat(tokens).hasSize(1000);
    }

    @Test
    void rejectsTamperedTokens() {
        assertThat(CartTokens.isWellFormed(null)).isFalse();
        assertThat(CartTokens.isWellFormed("")).isFalse();
        assertThat(CartTokens.isWellFormed(CartTokens.newToken() + "x")).isFalse();
        assertThat(CartTokens.isWellFormed("' or 1=1 --" + "a".repeat(32))).isFalse();
    }

    @Test
    void storesOnlyAHashOfTheToken() {
        var token = CartTokens.newToken();
        assertThat(CartTokens.sessionKey(token)).hasSize(64).isEqualTo(CartTokens.sessionKey(token))
                .isNotEqualTo(CartTokens.sessionKey(CartTokens.newToken()));
    }
}
