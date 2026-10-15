package com.unispark.ecommerce.customer;

import java.util.Locale;

/**
 * Delivers phone verification codes (SPEC 6.4). Implemented by the notification module: the customer module only
 * declares what it needs, so it does not depend on notifications, which in turn listen to orders.
 */
public interface CodeSender {

    /**
     * Sends at once, not through the job queue: a code is useful for minutes only.
     *
     * @param phone     in E.164
     * @param storeName shown in the message, so the shopper knows who asks
     */
    void sendCode(String phone, String code, String storeName, Locale locale);
}
