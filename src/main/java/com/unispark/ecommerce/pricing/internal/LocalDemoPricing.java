package com.unispark.ecommerce.pricing.internal;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.unispark.ecommerce.pricing.ExchangeRates;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreLookup;

/**
 * Local seed data (SPEC 15): a dollar rate for the demo stores, once per store without one. demo1 prices some
 * products and its Aleppo delivery in dollars but shows pounds, so without a rate its checkout refuses every order.
 */
@Component
@Profile("local")
@Order(70)
class LocalDemoPricing implements ApplicationRunner {

    /** Pounds per dollar, the rate the integration tests use. */
    static final String DEMO_RATE = "118.5";

    private final StoreLookup stores;
    private final ExchangeRates rates;

    LocalDemoPricing(StoreLookup stores, ExchangeRates rates) {
        this.stores = stores;
        this.rates = rates;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed("demo1");
        seed("demo2");
    }

    void seed(String slug) {
        stores.findBySlug(slug).ifPresent(store -> TenantContext.runAs(store.id(), () -> {
            if (rates.current().isEmpty()) {
                rates.set(DEMO_RATE, null);
            }
        }));
    }
}
