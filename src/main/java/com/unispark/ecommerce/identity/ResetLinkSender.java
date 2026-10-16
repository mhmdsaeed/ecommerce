package com.unispark.ecommerce.identity;

import java.util.Locale;

/**
 * Delivers password reset links. Implemented by the notification module, like {@code customer.CodeSender}: identity
 * only declares what it needs, so it does not depend on notifications (which depend on identity for contacts).
 */
public interface ResetLinkSender {

    /** Sends at once: the link works for {@link StaffPasswords#LINK_LIFETIME} only. */
    void send(ResetLink link);

    /**
     * Exactly one of {@code email} and {@code phone} is set: the one the member asked with.
     *
     * @param storeSlug and {@code token} make the link
     * @param storeName shown in the message, so the member knows which store it is about
     */
    record ResetLink(String email, String phone, String storeSlug, String token, String storeName, Locale locale) {
    }
}
