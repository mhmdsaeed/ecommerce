package com.unispark.ecommerce.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.unispark.ecommerce.identity.internal.Credentials.Email;
import com.unispark.ecommerce.identity.internal.Credentials.LoginIdentifier;
import com.unispark.ecommerce.identity.internal.Credentials.Phone;
import com.unispark.ecommerce.identity.internal.Credentials.Unusable;

class CredentialsTest {

    @Test
    void normalizesEmailsToTrimmedLowercase() {
        assertThat(Credentials.normalizeEmail("  Owner@Shop.SY ")).contains("owner@shop.sy");
        assertThat(Credentials.normalizeEmail("no-at-sign")).isEmpty();
        assertThat(Credentials.normalizeEmail("two@@signs.sy")).isEmpty();
        assertThat(Credentials.normalizeEmail("spaces in@shop.sy")).isEmpty();
    }

    @Test
    void acceptsPasswordsFromEightCharactersUpToBcryptsLimit() {
        assertThat(Credentials.passwordProblem("1234567")).contains("too-short");
        assertThat(Credentials.passwordProblem(null)).contains("too-short");
        assertThat(Credentials.passwordProblem("12345678")).isEmpty();
        assertThat(Credentials.passwordProblem("a".repeat(72))).isEmpty();
        assertThat(Credentials.passwordProblem("a".repeat(73))).contains("too-long");
    }

    @Test
    void countsCharactersNotBytesForTheMinimumAndBytesForTheMaximum() {
        assertThat(Credentials.passwordProblem("كلمةسرّية")).isEmpty();
        assertThat(Credentials.passwordProblem("س".repeat(37))).contains("too-long");
    }

    @Test
    void parsesTheLoginFieldAsEmailOrPhone() {
        assertThat(LoginIdentifier.parse("Owner@Shop.sy")).isEqualTo(new Email("owner@shop.sy"));
        assertThat(LoginIdentifier.parse("0933 123 456")).isEqualTo(new Phone("+963933123456"));
        assertThat(LoginIdentifier.parse("whatever")).isInstanceOf(Unusable.class);
        assertThat(LoginIdentifier.parse(null)).isInstanceOf(Unusable.class);
    }

    @Test
    void usesTheNormalizedFormAsRateLimitKey() {
        assertThat(LoginIdentifier.parse("0933123456").key())
                .isEqualTo(LoginIdentifier.parse("+963 933 123 456").key());
        assertThat(LoginIdentifier.parse(" Junk ").key()).isEqualTo("junk");
    }
}
