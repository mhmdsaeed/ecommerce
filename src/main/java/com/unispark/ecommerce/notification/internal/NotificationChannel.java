package com.unispark.ecommerce.notification.internal;

/**
 * A way of reaching people (SPEC 12). MVP ships the logging channel for development; SMS, WhatsApp and email
 * adapters join as further implementations (M11).
 */
sealed interface NotificationChannel permits LoggingChannel {

    void send(String recipient, String text);
}
