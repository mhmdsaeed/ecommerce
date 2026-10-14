package com.unispark.ecommerce.inventory.internal;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.unispark.ecommerce.catalog.Products;
import com.unispark.ecommerce.catalog.Products.VariantRow;
import com.unispark.ecommerce.inventory.Inventory;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreLookup;

/**
 * Local seed data (SPEC 15): stock for demo1's catalog, after {@code LocalDemoCatalog}. Every other variant is
 * tracked, with 5 on hand, and the very first is sold out; the rest stay untracked. Runs while nothing is tracked.
 */
@Component
@Profile("local")
@Order(40)
class LocalDemoStock implements ApplicationRunner {

    private final StoreLookup stores;
    private final Products products;
    private final Inventory inventory;

    LocalDemoStock(StoreLookup stores, Products products, Inventory inventory) {
        this.stores = stores;
        this.products = products;
        this.inventory = inventory;
    }

    @Override
    public void run(ApplicationArguments args) {
        stores.findBySlug("demo1").ifPresent(store -> TenantContext.runAs(store.id(), () -> {
            var variants = products.variants(null, 0).items();
            var ids = variants.stream().map(VariantRow::id).toList();
            if (variants.isEmpty() || !inventory.levels(ids).byVariant().isEmpty()) {
                return;
            }
            for (var index = 0; index < ids.size(); index += 2) {
                inventory.setOnHand(ids.get(index), index == 0 ? 0 : 5);
            }
        }));
    }
}
