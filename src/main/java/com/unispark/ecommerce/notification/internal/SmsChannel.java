package com.unispark.ecommerce.notification.internal;

import org.springframework.stereotype.Component;

import com.unispark.ecommerce.notification.NotificationLog.Channel;
import com.unispark.ecommerce.notification.internal.Message.Medium;

/**
 * Adapter stub for an SMS provider ({@code SMS_PROVIDER}, {@code SMS_API_KEY}). Which provider reaches Syrian
 * numbers reliably is still open (SPEC 19), so none is integrated: once a provider is configured, phone messages
 * route here and fail with a clear error in {@code notification_log} instead of silently going to the log.
 */
@Component
final class SmsChannel implements NotificationChannel {

    private final NotificationProperties.Sms properties;

    SmsChannel(NotificationProperties properties) {
        this.properties = properties.sms();
    }

    @Override
    public Channel kind() {
        return Channel.SMS;
    }

    @Override
    public boolean carries(Medium medium) {
        return medium == Medium.PHONE && properties.configured();
    }

    @Override
    public String send(Message message) {
        throw new UnsupportedOperationException(
                "SMS provider '" + properties.provider() + "' is not integrated yet");
    }
}
