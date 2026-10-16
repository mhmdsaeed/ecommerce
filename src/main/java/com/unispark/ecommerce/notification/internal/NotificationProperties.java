package com.unispark.ecommerce.notification.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Notification settings (SPEC 15). The SMTP server itself is {@code spring.mail.*} ({@code MAIL_HOST} and so on);
 * email goes out only when a host is set.
 *
 * @param mailFrom   sender address of emails ({@code MAIL_FROM}); required once a mail host is set
 * @param linkScheme scheme of links in messages, {@code https} except for plain-http local runs
 * @param sms        {@code SMS_PROVIDER} and {@code SMS_API_KEY}; blank provider means no SMS
 * @param whatsapp   {@code WHATSAPP_TOKEN} and {@code WHATSAPP_PHONE_ID}; blank token means no WhatsApp
 * @param logContent whether messages without a provider are logged in full (recipient and text, including
 *                   verification codes). Only for local runs and tests; otherwise the log gets a masked recipient
 *                   and no text, since logs leave the app (personal data, SPEC 10)
 */
@ConfigurationProperties("app.notification")
record NotificationProperties(
        String mailFrom,
        @DefaultValue("https") String linkScheme,
        @DefaultValue Sms sms,
        @DefaultValue WhatsApp whatsapp,
        @DefaultValue("false") boolean logContent) {

    record Sms(String provider, String apiKey) {

        boolean configured() {
            return provider != null && !provider.isBlank();
        }
    }

    record WhatsApp(String token, String phoneId) {

        boolean configured() {
            return token != null && !token.isBlank();
        }
    }
}
