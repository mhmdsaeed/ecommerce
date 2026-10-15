package com.unispark.ecommerce.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.FieldProblem;
import com.unispark.ecommerce.shared.Money;

/**
 * Invoices to stores and the payments recorded against them (SPEC 5, 8). Payments are recorded by the platform
 * admin; once an invoice is paid or voided and the store owes nothing else, a store that billing suspended reopens.
 */
public interface Invoices {

    int PAGE_SIZE = 50;
    int MAX_TEXT_LENGTH = 200;

    /** Newest first; {@code status} null for all. */
    InvoicePage list(InvoiceStatus status, int page);

    /** A store's invoices, newest first. */
    List<InvoiceView> ofStore(UUID storeId);

    Optional<InvoiceView> find(UUID invoiceId);

    /** An invoice written by hand (setup work, a correction), due on the given day. */
    CreateResult create(InvoiceDraft draft);

    /** Partial payments are fine; more than what is left to pay is refused. */
    PaymentResult recordPayment(UUID invoiceId, PaymentDraft draft, String recordedBy);

    /** Cancels an open invoice that should not be paid; it counts as settled. */
    VoidResult voidInvoice(UUID invoiceId, String reason);

    /** Where merchants send the money (a wallet number, a bank account), as configured; empty if not set. */
    Optional<String> payTo();

    /**
     * @param number    platform-wide, shown as INV-1001
     * @param paid      the payments so far
     * @param periodEnd null for invoices written by hand
     */
    record InvoiceView(UUID id, UUID storeId, long number, String description, String planCode, Money amount,
            Money paid, InvoiceStatus status, LocalDate issuedOn, LocalDate dueDate, LocalDate periodStart,
            LocalDate periodEnd, Instant paidAt, Instant voidedAt, String voidReason, List<PaymentView> payments) {

        public Money due() {
            return amount.minus(paid);
        }

        public String label() {
            return "INV-" + number;
        }
    }

    record PaymentView(InvoicePaymentMethod method, String reference, Money amount, String recordedBy,
            Instant recordedAt) {
    }

    record InvoicePage(List<InvoiceView> rows, int page, boolean hasNext) {
    }

    /** As typed in the form. */
    record InvoiceDraft(UUID storeId, String description, String amount, Currency currency, LocalDate dueDate) {
    }

    record PaymentDraft(InvoicePaymentMethod method, String amount, String reference) {
    }

    sealed interface CreateResult {
    }

    record Created(UUID invoiceId) implements CreateResult {
    }

    record Invalid(List<FieldProblem> problems) implements CreateResult, PaymentResult {
    }

    sealed interface PaymentResult {
    }

    /** @param settled the invoice is now fully paid */
    record PaymentRecorded(boolean settled) implements PaymentResult {
    }

    /** Not found, or no longer open. */
    record NotOpen() implements PaymentResult, VoidResult {
    }

    sealed interface VoidResult {
    }

    record Voided() implements VoidResult {
    }

    record ReasonRequired() implements VoidResult {
    }
}
