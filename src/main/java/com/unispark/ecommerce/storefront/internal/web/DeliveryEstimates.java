package com.unispark.ecommerce.storefront.internal.web;

import org.springframework.stereotype.Component;

import com.unispark.ecommerce.cart.Carts.CartView;
import com.unispark.ecommerce.delivery.DeliveryQuotes;
import com.unispark.ecommerce.delivery.DeliveryQuotes.NotDelivered;
import com.unispark.ecommerce.delivery.DeliveryZones;
import com.unispark.ecommerce.delivery.Destination;
import com.unispark.ecommerce.storefront.internal.web.DeliveryEstimate.Prompt;

/**
 * Builds the delivery box of the cart. The fee is quoted against the cart subtotal when every price is in one
 * currency; otherwise the fee is shown alone. A destination too vague for any zone asks for the next level (city,
 * then area) instead of saying the store does not deliver there.
 */
@Component
class DeliveryEstimates {

    private final DeliveryZones zones;
    private final DeliveryQuotes quotes;

    DeliveryEstimates(DeliveryZones zones, DeliveryQuotes quotes) {
        this.zones = zones;
        this.quotes = quotes;
    }

    DeliveryEstimate estimate(CartView cart, Destination destination) {
        var coverage = zones.coverage();
        if (coverage.isEmpty() || cart.isEmpty()) {
            return new DeliveryEstimate(coverage, null, null, null);
        }
        var governorate = destination == null ? null : coverage.of(destination.governorate()).orElse(null);
        if (governorate == null) {
            return new DeliveryEstimate(coverage, null, null, Prompt.GOVERNORATE);
        }
        var subtotal = cart.totals().size() == 1 ? cart.totals().getFirst() : null;
        var quote = quotes.quote(destination, subtotal);
        if (quote instanceof NotDelivered) {
            if (destination.city() == null && !governorate.cities().isEmpty()) {
                return new DeliveryEstimate(coverage, destination, null, Prompt.CITY);
            }
            if (destination.area() == null
                    && !coverage.areas(destination.governorate(), destination.city()).isEmpty()) {
                return new DeliveryEstimate(coverage, destination, null, Prompt.AREA);
            }
        }
        return new DeliveryEstimate(coverage, destination, quote, null);
    }
}
