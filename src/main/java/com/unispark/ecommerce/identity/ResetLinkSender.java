package com.unispark.ecommerce.identity;

import java.util.Locale;

/**
 * Delivers password reset links, and tells members when their password changed. Implemented by the notification
 * module, like {@code customer.CodeSender}: identity only declares what it needs, so it does not depend on
 * notifications (which depend on identity for contacts). Identity calls it off the request thread, after the
 * transaction commits, so how long a request takes does not tell whether an account exists.
 */
public interface ResetLinkSender {

    /** Sends at once: the link works for {@link StaffPasswords#LINK_LIFETIME} only. */
    void send(ResetLink link);

    /** Tells the member their password changed, so someone else changing it does not go unnoticed. */
    void passwordChanged(PasswordChanged notice);

    /**
     * Exactly one of {@code email} and {@code phone} is set: the one the member asked with.
     *
     * @param storeSlug and {@code token} make the link
     * @param storeName shown in the message, so the member knows which store it is about
     */
    record ResetLink(String email, String phone, String storeSlug, String token, String storeName, Locale locale) {
    }

    /** Exactly one of {@code email} and {@code phone} is set: the email when the member has one. */
    record PasswordChanged(String email, String phone, String storeName, Locale locale) {
    }
}
