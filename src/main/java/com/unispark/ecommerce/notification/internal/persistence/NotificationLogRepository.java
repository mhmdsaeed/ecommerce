package com.unispark.ecommerce.notification.internal.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/** Tenant-scoped. */
public interface NotificationLogRepository extends JpaRepository<NotificationLogEntry, UUID> {

    /** Locks the entry, so two runs of the same job cannot both send it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<NotificationLogEntry> findByDedupeKey(String dedupeKey);

    List<NotificationLogEntry> findByOrderIdOrderByCreatedAtAscIdAsc(UUID orderId);
}
