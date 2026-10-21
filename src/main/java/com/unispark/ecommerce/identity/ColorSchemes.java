package com.unispark.ecommerce.identity;

import java.util.UUID;

/** Each person's light or dark choice for the admin screens, remembered on their account. */
public interface ColorSchemes {

    /** A staff member of the store in {@code TenantContext}; DEVICE when there is none. */
    ColorScheme ofStaff(UUID staffId);

    void setForStaff(UUID staffId, ColorScheme scheme);

    /** A platform admin, by their login email; DEVICE when there is none. */
    ColorScheme ofPlatformUser(String email);

    void setForPlatformUser(String email, ColorScheme scheme);
}
