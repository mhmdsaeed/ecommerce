package com.unispark.ecommerce.storefront.internal.web;

import java.util.List;

import com.unispark.ecommerce.delivery.DeliveryQuotes.BelowMinimum;
import com.unispark.ecommerce.delivery.DeliveryQuotes.Deliverable;
import com.unispark.ecommerce.delivery.DeliveryQuotes.NotDelivered;
import com.unispark.ecommerce.delivery.DeliveryQuotes.Quote;
import com.unispark.ecommerce.delivery.DeliveryZones.Coverage;
import com.unispark.ecommerce.delivery.Destination;
import com.unispark.ecommerce.delivery.Governorate;

/**
 * The delivery box under the cart: where the store delivers, the destination the shopper chose, and its quote.
 *
 * @param destination null until the shopper picks a governorate the store delivers to
 * @param quote       null without a destination, or while {@code prompt} asks for more
 * @param prompt      what to ask for when there is nothing to quote yet: a governorate, a city, then an area
 */
public record DeliveryEstimate(Coverage coverage, Destination destination, Quote quote, Prompt prompt) {

    public enum Prompt {
        GOVERNORATE,
        CITY,
        AREA
    }

    public boolean shown() {
        return !coverage.isEmpty();
    }

    public Governorate governorate() {
        return destination == null ? null : destination.governorate();
    }

    public List<String> cities() {
        return destination == null ? List.of() : coverage.cities(destination.governorate());
    }

    public List<String> areas() {
        return destination == null || destination.city() == null ? List.of()
                : coverage.areas(destination.governorate(), destination.city());
    }

    public Deliverable deliverable() {
        return quote instanceof Deliverable deliverable ? deliverable : null;
    }

    public BelowMinimum belowMinimum() {
        return quote instanceof BelowMinimum belowMinimum ? belowMinimum : null;
    }

    public boolean notDelivered() {
        return quote instanceof NotDelivered;
    }
}
