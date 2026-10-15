package com.unispark.ecommerce.notification.internal;

import org.springframework.stereotype.Component;

import com.unispark.ecommerce.notification.NotificationLog.Channel;
import com.unispark.ecommerce.notification.internal.Message.Medium;

/**
 * Adapter stub for the WhatsApp Business API ({@code WHATSAPP_TOKEN}, {@code WHATSAPP_PHONE_ID}). Messages a
 * business starts need templates approved by Meta, which come with the provider decision (SPEC 19). Until then a
 * configured token routes phone messages here, and they fail with a clear error in {@code notification_log}.
 */
@Component
final class WhatsAppChannel implements NotificationChannel {

    private final NotificationProperties.WhatsApp properties;

    WhatsAppChannel(NotificationProperties properties) {
        this.properties = properties.whatsapp();
    }

    @Override
    public Channel kind() {
        return Channel.WHATSAPP;
    }

    @Override
    public boolean carries(Medium medium) {
        return medium == Medium.PHONE && properties.configured();
    }

    @Override
    public String send(Message message) {
        throw new UnsupportedOperationException("WhatsApp messages are not integrated yet");
    }
}
