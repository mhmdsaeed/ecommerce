package com.unispark.ecommerce.payment;

import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.Money;

/**
 * What each payment method means for an order (SPEC 6.3). Orders ask here instead of switching on the method, so a
 * new method (cards) is a new provider and nothing else.
 */
public interface Payments {

    /** The payment status a newly placed order starts in. */
    PaymentStatus initialStatus(PaymentMethod method);

    /** What the shopper needs to do to pay {@code amount}, as the order page shows it. */
    Instructions instructions(PaymentMethod method, Money amount);

    /**
     * @param displayName the merchant's name for the method, or null for the standard one
     * @param needsProof  the shopper sends a transaction reference after paying
     * @param accountId   null for cash on delivery
     */
    record Instructions(PaymentMethod method, LocalizedText displayName, Money amount, boolean needsProof,
            String accountId, String accountName, LocalizedText text) {
    }
}
