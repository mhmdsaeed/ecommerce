package com.unispark.ecommerce.notification;

import java.util.Locale;

/** Sends messages to people (SPEC 12). */
public interface Notifications {

    /**
     * Sends a phone verification code. Goes through the configured channel (SMS or WhatsApp once a provider is
     * set up; the application log until then).
     *
     * @param phone     in E.164
     * @param storeName shown in the message, so the shopper knows who asks
     */
    void sendCode(String phone, String code, String storeName, Locale locale);
}
