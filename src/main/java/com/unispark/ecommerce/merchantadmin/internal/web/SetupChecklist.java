package com.unispark.ecommerce.merchantadmin.internal.web;

import java.util.List;

import org.springframework.stereotype.Component;

import com.unispark.ecommerce.catalog.StorefrontCatalog;
import com.unispark.ecommerce.delivery.DeliveryZones;
import com.unispark.ecommerce.delivery.DeliveryZones.Zone;
import com.unispark.ecommerce.payment.PaymentMethods;
import com.unispark.ecommerce.tenant.StoreSettings;

/**
 * The setup wizard's steps (SPEC 9.3) for the store in {@code TenantContext}, worked out from what the store has:
 * details, look, payment methods, a delivery zone, a product shoppers can see, then going live. Look and details
 * are optional; the store can go live once shoppers could actually order: a way to pay, somewhere to deliver and
 * something to buy.
 */
@Component
class SetupChecklist {

    private final StoreSettings settings;
    private final PaymentMethods payments;
    private final DeliveryZones zones;
    private final StorefrontCatalog catalog;

    SetupChecklist(StoreSettings settings, PaymentMethods payments, DeliveryZones zones, StorefrontCatalog catalog) {
        this.settings = settings;
        this.payments = payments;
        this.zones = zones;
        this.catalog = catalog;
    }

    Checklist current() {
        var shop = settings.current();
        var theme = shop.theme();
        var styled = theme.logoKey() != null || theme.primaryColor() != null;
        var steps = List.of(
                new Step("details", "/admin/settings/store", shop.contactPhone() != null || shop.whatsapp() != null,
                        false),
                new Step("look", "/admin/settings/theme", styled, false),
                new Step("payments", "/admin/settings/payments", !payments.enabled().isEmpty(), true),
                new Step("delivery", "/admin/delivery", zones.list().stream().anyMatch(Zone::active), true),
                new Step("product", "/admin/products/new", !catalog.newest(1).isEmpty(), true));
        return new Checklist(steps, shop.launched());
    }

    /**
     * @param key      names the step's messages ({@code setup.step.<key>})
     * @param required the store cannot go live without it
     */
    public record Step(String key, String link, boolean done, boolean required) {
    }

    public record Checklist(List<Step> steps, boolean launched) {

        public boolean readyToLaunch() {
            return steps.stream().allMatch(step -> step.done() || !step.required());
        }

        public long done() {
            return steps.stream().filter(Step::done).count();
        }

        /** The share of steps still to do, 0 to 100; the setup page lowers the sign's shutter by it. */
        public long percentToDo() {
            return launched || steps.isEmpty() ? 0 : (steps.size() - done()) * 100 / steps.size();
        }
    }
}
