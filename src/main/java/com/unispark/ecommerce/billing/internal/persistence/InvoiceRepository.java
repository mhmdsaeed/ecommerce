package com.unispark.ecommerce.billing.internal.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.unispark.ecommerce.billing.InvoiceStatus;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    @Query(nativeQuery = true, value = "SELECT nextval('invoice_number_seq')")
    long nextNumber();

    /** Locks the invoice while a payment or a void is recorded. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = :id")
    Optional<Invoice> findByIdForUpdate(UUID id);

    List<Invoice> findByStoreIdOrderByIssuedOnDescNumberDesc(UUID storeId);

    List<Invoice> findAllByOrderByNumberDesc(Pageable page);

    List<Invoice> findByStatusOrderByNumberDesc(InvoiceStatus status, Pageable page);

    boolean existsByStoreIdAndStatus(UUID storeId, InvoiceStatus status);

    /** The open invoice that has waited longest, to name in a suspension. */
    Optional<Invoice> findFirstByStoreIdAndStatusOrderByDueDateAscNumberAsc(UUID storeId, InvoiceStatus status);
}
