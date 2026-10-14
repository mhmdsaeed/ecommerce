package com.unispark.ecommerce.tenant;

/**
 * Which part of the platform a request host belongs to (SPEC 4.1, 9). Each site serves its own routes:
 * <ul>
 *   <li>{@link #MAIN}: {@code <base>} (and {@code www.}, and hosts outside the base domain): signup, merchant
 *       admin {@code /admin}, driver {@code /driver};
 *   <li>{@link #PLATFORM_ADMIN}: {@code admin.<base>}: platform admin under {@code /platform};
 *   <li>{@link #STOREFRONT}: {@code <slug>.<base>}: the shopper-facing store.
 * </ul>
 */
public enum Site {
    MAIN,
    PLATFORM_ADMIN,
    STOREFRONT
}
