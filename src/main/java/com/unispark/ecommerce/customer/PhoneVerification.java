package com.unispark.ecommerce.customer;

import java.time.Duration;
import java.util.Locale;

/**
 * Confirms that a shopper holds a phone (SPEC 6.4), with a six-digit code sent through notifications. A correct code
 * yields a verification token that checkout accepts for {@link #VERIFIED_FOR}, so a failed checkout (stock, address)
 * does not ask for a new code. Codes and tokens are stored as hashes only.
 */
public interface PhoneVerification {

    Duration CODE_LIFETIME = Duration.ofMinutes(5);
    Duration VERIFIED_FOR = Duration.ofMinutes(30);
    /** Wrong guesses per code; a fresh code starts over. */
    int MAX_ATTEMPTS = 5;
    /** Codes per phone within {@link #SEND_WINDOW}, against message bombing and cost. */
    int MAX_CODES = 3;
    Duration SEND_WINDOW = Duration.ofMinutes(15);

    /** Sends a fresh code to the phone (any Syrian spelling). */
    SendResult send(String phone, Locale locale);

    /** Checks the latest code sent to the phone. */
    VerifyResult verify(String phone, String code);

    /** Whether {@code token} came from verifying this phone (any spelling) within {@link #VERIFIED_FOR}. */
    boolean isVerified(String phone, String token);

    sealed interface SendResult {
    }

    /** @param phone in E.164, as the code was sent */
    record Sent(String phone) implements SendResult {
    }

    record InvalidPhone() implements SendResult {
    }

    record TooManyCodes() implements SendResult {
    }

    sealed interface VerifyResult {
    }

    record Verified(String phone, String token) implements VerifyResult {
    }

    record WrongCode(int attemptsLeft) implements VerifyResult {
    }

    /** No live code for this phone: never sent, expired, used, or out of attempts. Ask for a new one. */
    record NoCode() implements VerifyResult {
    }
}
