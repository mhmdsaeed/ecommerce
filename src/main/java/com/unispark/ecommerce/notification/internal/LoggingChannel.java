package com.unispark.ecommerce.notification.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.unispark.ecommerce.notification.NotificationLog.Channel;
import com.unispark.ecommerce.notification.internal.Message.Medium;

/**
 * Writes messages to the application log instead of sending them: the fallback for every medium without a
 * configured provider. For development; messages may contain codes, so production configures real providers.
 */
@Component
final class LoggingChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(LoggingChannel.class);

    @Override
    public Channel kind() {
        return Channel.LOG;
    }

    @Override
    public boolean carries(Medium medium) {
        return true;
    }

    @Override
    public String send(Message message) {
        if (message.medium() == Medium.EMAIL) {
            log.info("Email to {}: {}\n{}", message.to(), message.subject(), message.text());
        } else {
            log.info("Message to {}: {}", message.to(), message.text());
        }
        return null;
    }
}
