package com.unispark.ecommerce.notification.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.unispark.ecommerce.notification.NotificationLog.Channel;
import com.unispark.ecommerce.notification.internal.Message.Medium;

/**
 * Writes messages to the application log instead of sending them: the fallback for every medium without a
 * configured provider. Local runs and tests log them in full ({@code app.notification.log-content}), so developers
 * can read verification codes. Everywhere else the log only says that a message was not delivered, to a masked
 * recipient, without its text: logs are shipped elsewhere, and messages carry phone numbers, addresses and codes.
 * The merchant still sees every message in the order's notification log.
 */
@Component
final class LoggingChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(LoggingChannel.class);

    private final boolean logContent;

    LoggingChannel(NotificationProperties properties) {
        this.logContent = properties.logContent();
    }

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
        if (!logContent) {
            log.warn("{} to {} not delivered: no provider is configured ({} characters withheld)", message.medium(),
                    mask(message.medium(), message.to()), message.text().length());
        } else if (message.medium() == Medium.EMAIL) {
            log.info("Email to {}: {}\n{}", message.to(), message.subject(), message.text());
        } else {
            log.info("Message to {}: {}", message.to(), message.text());
        }
        return null;
    }

    static String mask(Medium medium, String recipient) {
        return medium == Medium.EMAIL ? maskEmail(recipient) : maskPhone(recipient);
    }

    /** {@code +963944555666} → {@code +963******666}: the country and the last three digits. */
    static String maskPhone(String phone) {
        if (phone == null || phone.length() < 8) {
            return "***";
        }
        return phone.substring(0, 4) + "*".repeat(phone.length() - 7) + phone.substring(phone.length() - 3);
    }

    /** {@code owner@demo1.test} → {@code o***@demo1.test}. */
    static String maskEmail(String email) {
        var at = email == null ? -1 : email.lastIndexOf('@');
        if (at < 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
