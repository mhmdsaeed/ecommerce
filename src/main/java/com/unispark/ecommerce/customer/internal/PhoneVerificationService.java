package com.unispark.ecommerce.customer.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.customer.PhoneVerification;
import com.unispark.ecommerce.customer.internal.persistence.OtpChallenge;
import com.unispark.ecommerce.customer.internal.persistence.OtpChallengeRepository;
import com.unispark.ecommerce.notification.Notifications;
import com.unispark.ecommerce.shared.Digits;
import com.unispark.ecommerce.shared.PhoneNumbers;
import com.unispark.ecommerce.shared.SecretTokens;
import com.unispark.ecommerce.tenant.StoreSettings;

/**
 * Codes per store and phone. Limits are counted in the database, so they hold across instances: {@link #MAX_CODES}
 * sends per phone per {@link #SEND_WINDOW}, {@link #MAX_ATTEMPTS} guesses per code. Guesses on one code are
 * serialized by a row lock. The code is sent after the row is written, so a code always has its row.
 */
@Service
@Transactional
class PhoneVerificationService implements PhoneVerification {

    private final OtpChallengeRepository challenges;
    private final Notifications notifications;
    private final StoreSettings settings;
    private final Clock clock;

    PhoneVerificationService(OtpChallengeRepository challenges, Notifications notifications, StoreSettings settings,
            Clock clock) {
        this.challenges = challenges;
        this.notifications = notifications;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public SendResult send(String phone, Locale locale) {
        var normalized = PhoneNumbers.normalize(phone);
        if (normalized.isEmpty()) {
            return new InvalidPhone();
        }
        var now = clock.instant();
        if (challenges.countByPhoneAndCreatedAtAfter(normalized.get(), now.minus(SEND_WINDOW)) >= MAX_CODES) {
            return new TooManyCodes();
        }
        var code = SecretTokens.newCode();
        var challenge = new OtpChallenge(normalized.get(), now.plus(CODE_LIFETIME));
        challenge.setCodeHash(codeHash(challenge, code));
        challenges.saveAndFlush(challenge);
        notifications.sendCode(normalized.get(), code, settings.current().name().in(locale), locale);
        return new Sent(normalized.get());
    }

    @Override
    public VerifyResult verify(String phone, String code) {
        var normalized = PhoneNumbers.normalize(phone);
        if (normalized.isEmpty()) {
            return new NoCode();
        }
        var latest = challenges.findFirstByPhoneOrderByCreatedAtDescIdDesc(normalized.get())
                .filter(challenge -> challenge.isLive(clock.instant(), MAX_ATTEMPTS));
        if (latest.isEmpty()) {
            return new NoCode();
        }
        var challenge = latest.get();
        var typed = code == null ? "" : Digits.toAscii(code.strip());
        if (!matches(codeHash(challenge, typed), challenge.getCodeHash())) {
            challenge.failedAttempt();
            var left = MAX_ATTEMPTS - challenge.getAttempts();
            return left > 0 ? new WrongCode(left) : new NoCode();
        }
        var token = SecretTokens.newToken();
        challenge.verified(clock.instant(), SecretTokens.hash(token));
        return new Verified(normalized.get(), token);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isVerified(String phone, String token) {
        var normalized = PhoneNumbers.normalize(phone);
        if (normalized.isEmpty() || !SecretTokens.isWellFormed(token)) {
            return false;
        }
        return challenges.findByVerificationHash(SecretTokens.hash(token))
                .filter(challenge -> challenge.getPhone().equals(normalized.get()))
                .filter(challenge -> challenge.getVerifiedAt().plus(VERIFIED_FOR).isAfter(clock.instant()))
                .isPresent();
    }

    private static String codeHash(OtpChallenge challenge, String code) {
        return SecretTokens.hash(challenge.getId() + ":" + code);
    }

    /** Constant time, so response timing says nothing about how close a guess was. */
    private static boolean matches(String one, String other) {
        return MessageDigest.isEqual(one.getBytes(StandardCharsets.US_ASCII), other.getBytes(StandardCharsets.US_ASCII));
    }
}
