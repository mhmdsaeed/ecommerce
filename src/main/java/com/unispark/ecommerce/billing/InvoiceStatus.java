package com.unispark.ecommerce.billing;

/** An invoice is OPEN until fully paid (PAID) or cancelled (VOID). */
public enum InvoiceStatus {
    OPEN,
    PAID,
    VOID
}
