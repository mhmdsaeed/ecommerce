package com.unispark.ecommerce.identity;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import com.unispark.ecommerce.shared.FieldProblem;

/**
 * Staff changing their own password, and resetting a forgotten one with a link sent by email or text. Owners and
 * managers set others' passwords through {@link StaffManagement#setPassword}.
 */
public interface StaffPasswords {

    /** A reset link works once, for this long. */
    Duration LINK_LIFETIME = Duration.ofMinutes(30);

    /**
     * The actor's own password, after checking their current one. Their other sessions end; the returned principal
     * keeps this one going (log it in again with {@link StaffSessions}).
     */
    PasswordResult change(StaffPrincipal actor, String currentPassword, String newPassword);

    /**
     * Sends a reset link to the email or phone given, if it belongs to an active member of the store. Says nothing
     * either way, so the form does not reveal who has an account. Limited per client address and per account.
     */
    void requestReset(String storeSlug, String emailOrPhone, String clientAddress, Locale locale);

    /** Whether the link can still be used, to show the form or "expired" before anything is typed. */
    boolean resetLinkUsable(String storeSlug, String token);

    /** Sets the new password through a reset link, which then stops working. */
    PasswordResult reset(String storeSlug, String token, String newPassword);

    sealed interface PasswordResult {
    }

    /** @param principal the member with their new credentials version */
    record Changed(StaffPrincipal principal) implements PasswordResult {
    }

    /** @param problems e.g. ("currentPassword", "wrong"), ("newPassword", "too-short") */
    record Rejected(List<FieldProblem> problems) implements PasswordResult {
    }

    /** Unknown, used or expired link, or a store that is closed. */
    record LinkUnusable() implements PasswordResult {
    }
}
