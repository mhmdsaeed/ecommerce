package com.unispark.ecommerce.tenant;

/** Who suspended a store: billing (lifted when the store pays) or the platform admin (lifted only by hand). */
public enum SuspensionKind {
    BILLING,
    MANUAL
}
