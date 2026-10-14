package com.unispark.ecommerce.delivery.internal;

import org.springframework.stereotype.Component;

import com.unispark.ecommerce.delivery.DeliveryQuotes.NotDelivered;
import com.unispark.ecommerce.delivery.DeliveryQuotes.Quote;
import com.unispark.ecommerce.delivery.DeliveryZones;
import com.unispark.ecommerce.delivery.Destination;
import com.unispark.ecommerce.pricing.Prices;
import com.unispark.ecommerce.shared.Money;

/** The store's own drivers. The matching zone sets the fee, in display terms like every price shoppers see. */
@Component
final class MerchantDeliveryProvider implements ShippingProvider {

    private final DeliveryZones zones;
    private final Prices prices;

    MerchantDeliveryProvider(DeliveryZones zones, Prices prices) {
        this.zones = zones;
        this.prices = prices;
    }

    @Override
    public Quote quote(Destination destination, Money subtotal) {
        return zones.match(destination)
                .map(zone -> FeeRules.quote(zone, subtotal, amount -> prices.display(amount).amount()))
                .orElseGet(NotDelivered::new);
    }
}
