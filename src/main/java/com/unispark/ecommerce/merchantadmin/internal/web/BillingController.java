package com.unispark.ecommerce.merchantadmin.internal.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.unispark.ecommerce.billing.Invoices;
import com.unispark.ecommerce.billing.Plans;
import com.unispark.ecommerce.billing.Subscriptions;
import com.unispark.ecommerce.shared.TenantContext;

/**
 * The store's own billing (SPEC 9.3): its plan and subscription, its invoices, and how to pay them. Payments are
 * recorded by the platform once the money arrives. Owners and managers only.
 */
@Controller
@RequestMapping("/admin/billing")
@PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
class BillingController {

    private final Subscriptions subscriptions;
    private final Plans plans;
    private final Invoices invoices;

    BillingController(Subscriptions subscriptions, Plans plans, Invoices invoices) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.invoices = invoices;
    }

    @GetMapping
    String billing(Model model) {
        var store = TenantContext.requireTenantId();
        var subscription = subscriptions.forStore(store).orElse(null);
        model.addAttribute("subscription", subscription);
        model.addAttribute("plan", subscription == null ? null : plans.find(subscription.planCode()).orElse(null));
        model.addAttribute("invoices", invoices.ofStore(store));
        model.addAttribute("payTo", invoices.payTo().orElse(null));
        return "merchantadmin/billing";
    }
}
