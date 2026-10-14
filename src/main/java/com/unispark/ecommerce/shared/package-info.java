/**
 * Shared kernel: money, ids, tenant context, errors, base entities and web infrastructure.
 * Every module may depend on it; it depends on no other module.
 */
@ApplicationModule(displayName = "Shared")
package com.unispark.ecommerce.shared;

import org.springframework.modulith.ApplicationModule;
