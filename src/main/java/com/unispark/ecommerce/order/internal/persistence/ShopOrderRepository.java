package com.unispark.ecommerce.order.internal.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.unispark.ecommerce.order.OrderStatus;
import com.unispark.ecommerce.payment.PaymentStatus;

/** Tenant-scoped: only the current store's orders. */
public interface ShopOrderRepository extends JpaRepository<ShopOrder, UUID>, JpaSpecificationExecutor<ShopOrder> {

    Optional<ShopOrder> findByIdempotencyKey(UUID idempotencyKey);

    @EntityGraph(attributePaths = "items")
    Optional<ShopOrder> findByNumber(int number);

    /** Locked: status changes and proofs on one order apply one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from ShopOrder o where o.id = :id")
    Optional<ShopOrder> findForUpdate(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from ShopOrder o where o.number = :number")
    Optional<ShopOrder> findByNumberForUpdate(int number);

    long countByCustomerIdAndStatusIn(UUID customerId, Collection<OrderStatus> statuses);

    long countByPlacedAtGreaterThanEqual(Instant since);

    List<ShopOrder> findByDriverIdAndStatusInOrderByPlacedAtAscNumberAsc(UUID driverId,
            Collection<OrderStatus> statuses);

    long countByStatus(OrderStatus status);

    long countByPaymentStatus(PaymentStatus paymentStatus);
}
