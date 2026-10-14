/**
 * Currencies, exchange rates, price conversion, rounding and formatting (SPEC 7). Prices are stored in the
 * product's currency; shoppers see them in the store's display currency, converted at the merchant's own
 * USD to SYP rate and rounded by the store's rule.
 */
@ApplicationModule(displayName = "Pricing")
package com.unispark.ecommerce.pricing;

import org.springframework.modulith.ApplicationModule;
