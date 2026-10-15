package com.unispark.ecommerce.notification.internal;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.unispark.ecommerce.notification.internal.Message.Medium;

/**
 * Picks the channel per medium: email over SMTP; for phones WhatsApp, then SMS; the application log when nothing
 * is configured. Logs the choice once at startup, so a missing provider is visible.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationProperties.class)
class ChannelRouter {

    private static final Logger log = LoggerFactory.getLogger(ChannelRouter.class);

    private final List<NotificationChannel> preference;

    ChannelRouter(EmailChannel email, WhatsAppChannel whatsapp, SmsChannel sms, LoggingChannel logging) {
        this.preference = List.of(email, whatsapp, sms, logging);
        for (var medium : Medium.values()) {
            log.info("Notifications by {} go through {}", medium, channelFor(medium).kind());
        }
    }

    NotificationChannel channelFor(Medium medium) {
        return preference.stream().filter(channel -> channel.carries(medium)).findFirst().orElseThrow();
    }
}
