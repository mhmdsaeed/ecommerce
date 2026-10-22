/**
 * Store reports (sales, products, orders and delivery, customers and payments). A read model: it queries the order,
 * catalog, inventory, delivery and customer tables with SQL, read-only, inside the store's tenant context so
 * row-level security applies, and writes nothing. Other modules' Java types stay out of it apart from their APIs.
 */
@ApplicationModule(displayName = "Report")
package com.unispark.ecommerce.report;

import org.springframework.modulith.ApplicationModule;
