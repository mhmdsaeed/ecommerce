package com.unispark.ecommerce.tenant;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.unispark.ecommerce.shared.LocalizedText;

/**
 * Store lifecycle for the platform: suspending and activating stores (SPEC 9.4) and billing's suspensions for
 * unpaid invoices (SPEC 8). For the platform admin and billing modules only.
 */
public interface StoreAdministration {

    Optional<StoreRecord> find(UUID storeId);

    /**
     * The platform admin suspends a store, whatever its reason; only the platform admin lifts it.
     *
     * @param reason shown to platform admins; required
     */
    StatusChange suspend(UUID storeId, String reason);

    /**
     * The platform admin activates a TRIAL or SUSPENDED store. The first activation needs the sanctions and KYC
     * screening to be confirmed; it is recorded with its notes and who confirmed it.
     *
     * @param screened the admin ticked "screened"; ignored once a store has been screened
     * @param by       the platform admin's email
     */
    StatusChange activate(UUID storeId, boolean screened, String notes, String by);

    /** Billing suspends a TRIAL or ACTIVE store for an unpaid invoice. Returns whether it did. */
    boolean suspendForNonPayment(UUID storeId, String reason);

    /**
     * Billing lifts its own suspension once nothing is owed: the store goes back to ACTIVE, or TRIAL if never
     * screened. A store the platform admin suspended stays suspended. Returns whether it reopened.
     */
    boolean resumeAfterPayment(UUID storeId);

    /**
     * @param suspensionKind null unless suspended
     * @param screenedAt     null until the platform admin first activates it
     */
    record StoreRecord(UUID id, String slug, LocalizedText name, StoreStatus status, String contactPhone,
            Instant createdAt, SuspensionKind suspensionKind, String suspensionReason, Instant screenedAt,
            String screenedBy, String screeningNotes) {
    }

    sealed interface StatusChange {
    }

    record Changed(StoreStatus status) implements StatusChange {
    }

    /** Not found, closed, or already in the requested state. */
    record NotApplicable() implements StatusChange {
    }

    /** A first activation without the screening confirmed. */
    record ScreeningRequired() implements StatusChange {
    }

    /** A suspension needs a reason. */
    record ReasonRequired() implements StatusChange {
    }
}
