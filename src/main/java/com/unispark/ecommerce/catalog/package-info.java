/**
 * Categories, products, options, variants and product images (SPEC 3, 5). All data is tenant-scoped: every
 * operation works on the store in {@code TenantContext}.
 */
@ApplicationModule(displayName = "Catalog")
package com.unispark.ecommerce.catalog;

import org.springframework.modulith.ApplicationModule;
