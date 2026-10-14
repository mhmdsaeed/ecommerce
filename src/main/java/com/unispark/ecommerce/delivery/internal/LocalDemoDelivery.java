package com.unispark.ecommerce.delivery.internal;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.unispark.ecommerce.delivery.DeliveryZones;
import com.unispark.ecommerce.delivery.DeliveryZones.ZoneDraft;
import com.unispark.ecommerce.delivery.Governorate;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreLookup;

/**
 * Local seed data (SPEC 15): delivery zones for the demo stores, once per store without zones. demo1 shows every
 * level: all of Rif Dimashq, the city of Damascus with one cheaper area, and Aleppo priced in dollars.
 */
@Component
@Profile("local")
@Order(50)
class LocalDemoDelivery implements ApplicationRunner {

    private final StoreLookup stores;
    private final DeliveryZones zones;

    LocalDemoDelivery(StoreLookup stores, DeliveryZones zones) {
        this.stores = stores;
        this.zones = zones;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed("demo1",
                new ZoneDraft(Governorate.DAMASCUS, "دمشق", null, "2000", Currency.SYP, null, "50000",
                        "خلال 24 ساعة", "Within 24 hours"),
                new ZoneDraft(Governorate.DAMASCUS, "دمشق", "المزة", "1500", Currency.SYP, null, "50000",
                        "في اليوم نفسه", "Same day"),
                new ZoneDraft(Governorate.RIF_DIMASHQ, null, null, "3500", Currency.SYP, "10000", null,
                        "خلال يومين", "Within 2 days"),
                new ZoneDraft(Governorate.ALEPPO, "حلب", null, "5", Currency.USD, "20", null,
                        "خلال 3 أيام", "Within 3 days"));
        seed("demo2",
                new ZoneDraft(Governorate.HOMS, "حمص", null, "2500", Currency.SYP, null, null, null, null));
    }

    private void seed(String slug, ZoneDraft... drafts) {
        stores.findBySlug(slug).ifPresent(store -> TenantContext.runAs(store.id(), () -> {
            if (!zones.list().isEmpty()) {
                return;
            }
            for (var draft : drafts) {
                zones.create(draft);
            }
        }));
    }
}
