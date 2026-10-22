package com.unispark.ecommerce.pricing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.unispark.ecommerce.TestStores;
import com.unispark.ecommerce.TestcontainersConfig;
import com.unispark.ecommerce.pricing.ExchangeRates;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreLookup;

@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class LocalDemoPricingIT {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StoreLookup stores;

    @Autowired
    ExchangeRates rates;

    String slug;
    UUID store;
    LocalDemoPricing seed;

    @BeforeEach
    void createStore() {
        slug = TestStores.randomSlug();
        store = TestStores.create(jdbc, slug, "ACTIVE");
        seed = new LocalDemoPricing(stores, rates);
    }

    @Test
    void givesADemoStoreWithoutARateTheDemoRate() {
        seed.seed(slug);

        assertThat(rate()).isEqualByComparingTo(LocalDemoPricing.DEMO_RATE);
    }

    @Test
    void keepsARateTheMerchantSet() {
        TenantContext.runAs(store, () -> rates.set("130", null));

        seed.seed(slug);

        assertThat(rate()).isEqualByComparingTo("130");
        assertThat(TenantContext.callAs(store, () -> rates.history(10))).hasSize(1);
    }

    private BigDecimal rate() {
        return TenantContext.callAs(store, () -> rates.current().orElseThrow().sypPerUsd());
    }
}
