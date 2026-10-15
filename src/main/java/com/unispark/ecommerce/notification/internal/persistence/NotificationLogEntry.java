package com.unispark.ecommerce.notification.internal.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.unispark.ecommerce.notification.NotificationLog.Channel;
import com.unispark.ecommerce.notification.NotificationLog.Status;
import com.unispark.ecommerce.shared.TenantScopedEntity;

/** One message to one recipient, and how sending it went. */
@Entity
@Table(name = "notification_log")
public class NotificationLogEntry extends TenantScopedEntity {

    /** Longest error kept; provider messages can be long. */
    static final int MAX_ERROR_LENGTH = 500;

    @Column(name = "dedupe_key", nullable = false, updatable = false)
    private String dedupeKey;

    @Column(nullable = false, updatable = false)
    private String template;

    @Column(name = "order_id", updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    @Column(nullable = false, updatable = false)
    private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "provider_ref")
    private String providerRef;

    private String error;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected NotificationLogEntry() {
    }

    public NotificationLogEntry(String dedupeKey, String template, UUID orderId, Channel channel, String recipient) {
        this.dedupeKey = dedupeKey;
        this.template = template;
        this.orderId = orderId;
        this.channel = channel;
        this.recipient = recipient;
    }

    /** A send is starting through {@code through}; until it ends the entry stays pending. */
    public void attempt(Channel through) {
        channel = through;
        status = Status.PENDING;
        attempts++;
    }

    public void sent(String reference, Instant at) {
        status = Status.SENT;
        providerRef = reference;
        error = null;
        sentAt = at;
    }

    public void failed(String reason) {
        status = Status.FAILED;
        error = reason == null || reason.length() <= MAX_ERROR_LENGTH ? reason : reason.substring(0, MAX_ERROR_LENGTH);
    }

    public String getTemplate() {
        return template;
    }

    public Channel getChannel() {
        return channel;
    }

    public String getRecipient() {
        return recipient;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getError() {
        return error;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
