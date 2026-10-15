package com.unispark.ecommerce.notification.internal;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.customer.CodeSender;
import com.unispark.ecommerce.notification.NotificationLog;
import com.unispark.ecommerce.notification.internal.persistence.NotificationLogEntry;
import com.unispark.ecommerce.notification.internal.persistence.NotificationLogRepository;

/** Verification codes, sent at once, and the log of what was sent. */
@Service
class NotificationService implements CodeSender, NotificationLog {

    private final Deliveries deliveries;
    private final NotificationTexts texts;
    private final NotificationLogRepository entries;

    NotificationService(Deliveries deliveries, NotificationTexts texts, NotificationLogRepository entries) {
        this.deliveries = deliveries;
        this.texts = texts;
        this.entries = entries;
    }

    /** Each code is its own message; a failure is logged and recorded, and the shopper can ask for another. */
    @Override
    public void sendCode(String phone, String code, String storeName, Locale locale) {
        deliveries.deliver("otp:" + UUID.randomUUID(), Template.OTP, null, texts.code(phone, code, storeName, locale));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Entry> forOrder(UUID orderId) {
        return entries.findByOrderIdOrderByCreatedAtAscIdAsc(orderId).stream().map(NotificationService::entry).toList();
    }

    private static Entry entry(NotificationLogEntry entry) {
        return new Entry(entry.getTemplate(), entry.getChannel(), entry.getRecipient(), entry.getStatus(),
                entry.getAttempts(), entry.getSentAt() != null ? entry.getSentAt() : entry.getUpdatedAt(),
                entry.getError());
    }
}
