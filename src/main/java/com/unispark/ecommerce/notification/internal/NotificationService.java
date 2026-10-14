package com.unispark.ecommerce.notification.internal;

import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import com.unispark.ecommerce.notification.Notifications;

/** Message texts come from the bundles, in the recipient's language. */
@Service
class NotificationService implements Notifications {

    private final NotificationChannel channel;
    private final MessageSource messages;

    NotificationService(NotificationChannel channel, MessageSource messages) {
        this.channel = channel;
        this.messages = messages;
    }

    @Override
    public void sendCode(String phone, String code, String storeName, Locale locale) {
        channel.send(phone, messages.getMessage("notification.code", new Object[] {code, storeName}, locale));
    }
}
