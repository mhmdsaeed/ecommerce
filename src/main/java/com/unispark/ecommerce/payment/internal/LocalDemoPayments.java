package com.unispark.ecommerce.payment.internal;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.unispark.ecommerce.payment.PaymentMethod;
import com.unispark.ecommerce.payment.PaymentMethods;
import com.unispark.ecommerce.payment.PaymentMethods.MethodDraft;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreLookup;

/**
 * Local seed data (SPEC 15): demo1 accepts Sham Cash besides cash on delivery, so both kinds of checkout can be
 * tried. Runs while demo1 has no transfer method enabled.
 */
@Component
@Profile("local")
@Order(60)
class LocalDemoPayments implements ApplicationRunner {

    private final StoreLookup stores;
    private final PaymentMethods methods;

    LocalDemoPayments(StoreLookup stores, PaymentMethods methods) {
        this.stores = stores;
        this.methods = methods;
    }

    @Override
    public void run(ApplicationArguments args) {
        stores.findBySlug("demo1").ifPresent(store -> TenantContext.runAs(store.id(), () -> {
            if (methods.enabled().stream().anyMatch(config -> config.method().isTransfer())) {
                return;
            }
            methods.save(PaymentMethod.SHAM_CASH, new MethodDraft(true, null, null, "0944 123 456", "متجر الياسمين",
                    "حوّل المبلغ ثم أرسل رقم العملية من صفحة طلبك.",
                    "Transfer the amount, then send the transaction number from your order page."));
        }));
    }
}
