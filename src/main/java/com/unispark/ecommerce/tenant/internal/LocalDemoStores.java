package com.unispark.ecommerce.tenant.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.tenant.StoreStatus;
import com.unispark.ecommerce.tenant.internal.persistence.Store;
import com.unispark.ecommerce.tenant.internal.persistence.StoreRepository;

/**
 * Local seed data (SPEC 15): the demo stores {@code demo1} and {@code demo2}, reachable at
 * {@code http://demo1.lvh.me:8080}. Idempotent. Later milestones add their products, zones and payment methods.
 */
@Component
@Profile("local")
class LocalDemoStores implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalDemoStores.class);

    private final StoreRepository stores;

    LocalDemoStores(StoreRepository stores) {
        this.stores = stores;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed("demo1", LocalizedText.of("متجر الياسمين", "Jasmine Store"));
        seed("demo2", LocalizedText.of("دكان الشام", "Sham Shop"));
    }

    private void seed(String slug, LocalizedText name) {
        if (!stores.existsBySlug(slug)) {
            stores.save(new Store(slug, name, StoreStatus.ACTIVE));
            log.info("Seeded demo store {}", slug);
        }
    }
}
