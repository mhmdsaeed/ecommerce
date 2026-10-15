package com.unispark.ecommerce.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What was sent to whom, for the current store (SPEC 5 {@code notification_log}). */
public interface NotificationLog {

    /** The messages about one order, oldest first. */
    List<Entry> forOrder(UUID orderId);

    /** {@code LOG}: no provider is configured for the medium, so the message only went to the application log. */
    enum Channel {
        EMAIL, SMS, WHATSAPP, LOG
    }

    /** {@code PENDING} while a send is under way or was interrupted; {@code FAILED} is retried by its job. */
    enum Status {
        PENDING, SENT, FAILED
    }

    /**
     * @param template e.g. {@code order_confirmed}
     * @param at       when it was sent, or last tried
     * @param error    the last failure, or null
     */
    record Entry(String template, Channel channel, String recipient, Status status, int attempts, Instant at,
            String error) {
    }
}
