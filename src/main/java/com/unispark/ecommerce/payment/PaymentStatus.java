package com.unispark.ecommerce.payment;

/**
 * Where an order's payment stands (SPEC 6.2): transfers go UNPAID, AWAITING_VERIFICATION once the shopper sends a
 * proof, then PAID or REJECTED (the shopper may send another). Cash on delivery goes from UNPAID to PAID when the
 * driver collects.
 */
public enum PaymentStatus {
    UNPAID,
    AWAITING_VERIFICATION,
    PAID,
    REJECTED;

    /** The shopper can send (another) proof of a transfer. */
    public boolean awaitsProof() {
        return this == UNPAID || this == REJECTED;
    }
}
