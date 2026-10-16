package com.unispark.ecommerce.identity.internal;

import java.time.Clock;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.unispark.ecommerce.identity.internal.persistence.PasswordResetRepository;
import com.unispark.ecommerce.identity.internal.persistence.StaffUser;

/**
 * The one way a staff password changes, whoever changes it: the new hash, a new credentials version (ending the
 * member's other sessions), and every reset link still open for them closed. Callers check the password first
 * ({@link Credentials#passwordProblem}) and run inside a transaction in the member's tenant.
 */
@Component
class PasswordChanges {

    private final PasswordEncoder passwords;
    private final PasswordResetRepository resets;
    private final Clock clock;

    PasswordChanges(PasswordEncoder passwords, PasswordResetRepository resets, Clock clock) {
        this.passwords = passwords;
        this.resets = resets;
        this.clock = clock;
    }

    void change(StaffUser user, String newPassword) {
        user.changePassword(passwords.encode(newPassword));
        var now = clock.instant();
        resets.findByStaffIdAndUsedAtIsNullAndExpiresAtAfter(user.getId(), now).forEach(reset -> reset.use(now));
    }
}
