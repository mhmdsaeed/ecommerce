package com.unispark.ecommerce.delivery.internal;

import com.unispark.ecommerce.delivery.DeliveryQuotes.Quote;
import com.unispark.ecommerce.delivery.Destination;
import com.unispark.ecommerce.shared.Money;

/**
 * Who delivers and what it costs (SPEC 8). MVP has one provider, the merchant's own drivers priced by zones. A
 * courier provider (courier name and tracking reference) is phase 2 and joins as another implementation.
 */
sealed interface ShippingProvider permits MerchantDeliveryProvider {

    Quote quote(Destination destination, Money subtotal);
}
