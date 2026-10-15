package com.unispark.ecommerce.billing.internal.persistence;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.unispark.ecommerce.billing.SubscriptionStatus;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    Optional<Subscription> findByStoreId(UUID storeId);

    /** Locks the subscription: the daily job, payments and plan changes never update it at the same time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subscription s where s.storeId = :storeId")
    Optional<Subscription> findByStoreIdForUpdate(UUID storeId);

    List<Subscription> findAllByOrderByPeriodEndAsc();

    List<Subscription> findByStatusOrderByPeriodEndAsc(SubscriptionStatus status);

    @Query("select s.storeId from Subscription s where s.status in :statuses and s.periodEnd <= :today")
    List<UUID> findStoresToBill(Collection<SubscriptionStatus> statuses, LocalDate today);

    @Query("""
            select s.storeId from Subscription s
            where s.status = com.unispark.ecommerce.billing.SubscriptionStatus.PAST_DUE and s.graceUntil < :today
            """)
    List<UUID> findStoresPastGrace(LocalDate today);

    /**
     * Starts a trial for every store without a subscription, in one statement; a store that got one meanwhile is
     * skipped. Returns how many started.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO subscription (id, store_id, plan_code, status, current_period_start, current_period_end,
                                      created_at, updated_at, version)
            SELECT gen_random_uuid(), s.id, :planCode, 'TRIAL', :today, :trialEnd, now(), now(), 0
            FROM store s
            WHERE s.status <> 'CLOSED' AND NOT EXISTS (SELECT 1 FROM subscription x WHERE x.store_id = s.id)
            ON CONFLICT (store_id) DO NOTHING
            """)
    int startMissingTrials(String planCode, LocalDate today, LocalDate trialEnd);
}
