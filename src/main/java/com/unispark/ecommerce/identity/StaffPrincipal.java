package com.unispark.ecommerce.identity;

import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

/**
 * The logged-in staff member, kept in the HTTP session. {@code tenantId} is the store id: merchant admin and
 * driver requests run in that tenant (SPEC 4.1). Holds no credentials.
 */
public record StaffPrincipal(UUID staffId, UUID tenantId, String storeSlug, String name, StaffRole role)
        implements Principal, Serializable {

    @Override
    public String getName() {
        return staffId.toString();
    }
}
