package com.unispark.ecommerce.identity.internal;

import java.time.Clock;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.unispark.ecommerce.identity.ResetLinkSender.PasswordChanged;
import com.unispark.ecommerce.identity.internal.persistence.PasswordResetRepository;
import com.unispark.ecommerce.identity.internal.persistence.StaffUser;
import com.unispark.ecommerce.tenant.StoreSettings;

/**
 * The one way a staff password changes, whoever changes it: the new hash, a new credentials version (ending the
 * member's other sessions), every reset link still open for them closed, and a message telling them, so a change
 * they did not make does not go unnoticed. Callers check the password first ({@link Credentials#passwordProblem})
 * and run inside a transaction in the member's tenant.
 */
@Component
class PasswordChanges {

    private final PasswordEncoder passwords;
    private final PasswordResetRepository resets;
    private final PasswordMessages messages;
    private final StoreSettings settings;
    private final Clock clock;

    PasswordChanges(PasswordEncoder passwords, PasswordResetRepository resets, PasswordMessages messages,
            StoreSettings settings, Clock clock) {
        this.passwords = passwords;
        this.resets = resets;
        this.messages = messages;
        this.settings = settings;
        this.clock = clock;
    }

    void change(StaffUser user, String newPassword) {
        user.changePassword(passwords.encode(newPassword));
        closeLinks(user.getId());
        if (user.getEmail() != null || user.getPhone() != null) {
            var store = settings.current();
            var locale = store.numberDigits().apply(store.defaultLocale());
            messages.passwordChanged(new PasswordChanged(user.getEmail(),
                    user.getEmail() == null ? user.getPhone() : null, store.name().in(locale), locale));
        }
    }

    /** Every reset link still open for the member stops working. */
    void closeLinks(UUID staffId) {
        var now = clock.instant();
        resets.findByStaffIdAndUsedAtIsNullAndExpiresAtAfter(staffId, now).forEach(reset -> reset.use(now));
    }
}
