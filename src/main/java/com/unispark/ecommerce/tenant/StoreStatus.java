package com.unispark.ecommerce.tenant;

/** Store lifecycle (SPEC 5). Suspended stores keep their admin but show shoppers a "temporarily unavailable" page. */
public enum StoreStatus {
    TRIAL,
    ACTIVE,
    SUSPENDED,
    CLOSED;

    /** Closed stores are gone for shoppers: their storefront answers 404 like an unknown store. */
    public boolean hasStorefront() {
        return this != CLOSED;
    }
}
