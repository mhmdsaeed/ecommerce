package com.unispark.ecommerce.notification.internal;

import java.time.Clock;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.unispark.ecommerce.notification.NotificationLog.Status;
import com.unispark.ecommerce.notification.internal.persistence.NotificationLogEntry;
import com.unispark.ecommerce.notification.internal.persistence.NotificationLogRepository;

/**
 * Sends one message and records it, in the store of {@code TenantContext}. Each message has its own transaction
 * (independent of any caller's), holding the entry's row lock while sending: a message already sent is skipped, so
 * retried jobs and redelivered events send only what has not gone out yet.
 */
@Component
class Deliveries {

    private static final Logger log = LoggerFactory.getLogger(Deliveries.class);

    private final NotificationLogRepository entries;
    private final ChannelRouter router;
    private final TransactionTemplate transaction;
    private final Clock clock;

    Deliveries(NotificationLogRepository entries, ChannelRouter router, PlatformTransactionManager transactions,
            Clock clock) {
        this.entries = entries;
        this.router = router;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /**
     * @param dedupeKey names the message, so it is sent once
     * @param orderId   the order it is about, or null
     * @return whether the message has been sent, now or before
     */
    boolean deliver(String dedupeKey, Template template, UUID orderId, Message message) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var channel = router.channelFor(message.medium());
            var entry = entries.findByDedupeKey(dedupeKey).orElseGet(() -> entries.saveAndFlush(
                    new NotificationLogEntry(dedupeKey, template.code(), orderId, channel.kind(), message.to())));
            if (entry.getStatus() == Status.SENT) {
                return true;
            }
            entry.attempt(channel.kind());
            try {
                entry.sent(channel.send(message), clock.instant());
                return true;
            } catch (RuntimeException e) {
                log.warn("Could not send {} to {} through {}", template.code(), message.to(), channel.kind(), e);
                entry.failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                return false;
            }
        }));
    }
}
