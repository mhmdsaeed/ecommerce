package com.unispark.ecommerce.delivery.internal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.delivery.DeliveryQuotes;
import com.unispark.ecommerce.delivery.Destination;
import com.unispark.ecommerce.shared.Money;

@Service
@Transactional(readOnly = true)
class DeliveryQuoteService implements DeliveryQuotes {

    private final ShippingProvider provider;

    DeliveryQuoteService(ShippingProvider provider) {
        this.provider = provider;
    }

    @Override
    public Quote quote(Destination destination, Money subtotal) {
        return provider.quote(destination, subtotal);
    }
}
