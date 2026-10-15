package com.unispark.ecommerce.billing.internal.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoicePaymentRepository extends JpaRepository<InvoicePayment, UUID> {

    List<InvoicePayment> findByInvoiceIdInOrderByRecordedAtAsc(Collection<UUID> invoiceIds);
}
