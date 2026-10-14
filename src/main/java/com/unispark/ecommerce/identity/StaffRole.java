package com.unispark.ecommerce.identity;

import java.util.Set;

/**
 * Roles of a store's staff (SPEC 5). Owners and managers run the store, staff handle day-to-day work in the
 * merchant admin, and drivers only see the delivery view.
 */
public enum StaffRole {
    OWNER,
    MANAGER,
    STAFF,
    DRIVER;

    /** The Spring Security authority, e.g. {@code ROLE_OWNER}. */
    public String authority() {
        return "ROLE_" + name();
    }

    public boolean usesMerchantAdmin() {
        return this != DRIVER;
    }

    public boolean managesStaff() {
        return this == OWNER || this == MANAGER;
    }

    /** Whether someone with this role may add, enable or disable staff with {@code other}'s role. */
    public boolean canManage(StaffRole other) {
        return switch (this) {
            case OWNER -> other != OWNER;
            case MANAGER -> Set.of(STAFF, DRIVER).contains(other);
            case STAFF, DRIVER -> false;
        };
    }
}
