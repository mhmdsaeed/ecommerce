/**
 * Platform admin web on {@code admin.<base>}, under {@code /platform} (SPEC 9.4). Stores, plans, subscriptions and
 * invoices arrive with billing (M12). May use {@link com.unispark.ecommerce.shared.PlatformDatabase}.
 */
@ApplicationModule(displayName = "Platform admin")
package com.unispark.ecommerce.platformadmin;

import org.springframework.modulith.ApplicationModule;
