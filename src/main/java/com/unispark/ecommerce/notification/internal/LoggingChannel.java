package com.unispark.ecommerce.notification.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes messages to the application log instead of sending them. For development and until a real provider is
 * configured: messages may contain codes, so production must not rely on it (M11 adds the providers).
 */
@Component
final class LoggingChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(LoggingChannel.class);

    @Override
    public void send(String recipient, String text) {
        log.info("Message to {}: {}", recipient, text);
    }
}
