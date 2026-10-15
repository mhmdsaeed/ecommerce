package com.unispark.ecommerce.platformadmin.internal.web;

import java.security.Principal;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import com.unispark.ecommerce.billing.Invoices;
import com.unispark.ecommerce.billing.Plans;
import com.unispark.ecommerce.billing.Plans.PlanView;
import com.unispark.ecommerce.billing.Subscriptions;
import com.unispark.ecommerce.billing.Subscriptions.SubscriptionView;
import com.unispark.ecommerce.shared.ListParams;
import com.unispark.ecommerce.tenant.StoreAdministration;
import com.unispark.ecommerce.tenant.StoreAdministration.Changed;
import com.unispark.ecommerce.tenant.StoreAdministration.NotApplicable;
import com.unispark.ecommerce.tenant.StoreAdministration.ReasonRequired;
import com.unispark.ecommerce.tenant.StoreAdministration.ScreeningRequired;
import com.unispark.ecommerce.tenant.StoreStatus;

/**
 * Stores across the platform (SPEC 9.4): the list with their subscriptions, and a store's page, where the platform
 * admin suspends it, activates it (recording the sanctions and KYC screening the first time), changes its plan
 * and sees its invoices.
 */
@Controller
@RequestMapping("/platform/stores")
class PlatformStoresController {

    private static final Set<String> DONE = Set.of("suspended", "activated", "plan");
    private static final Set<String> ERRORS = Set.of("screening", "reason", "status", "plan");

    private final StoreNames storeNames;
    private final StoreAdministration stores;
    private final Subscriptions subscriptions;
    private final Plans plans;
    private final Invoices invoices;

    PlatformStoresController(StoreNames storeNames, StoreAdministration stores, Subscriptions subscriptions,
            Plans plans, Invoices invoices) {
        this.storeNames = storeNames;
        this.stores = stores;
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.invoices = invoices;
    }

    @GetMapping
    String list(@RequestParam(required = false) String status, Model model) {
        var filter = ListParams.constant(StoreStatus.class, status).orElse(null);
        model.addAttribute("status", filter);
        model.addAttribute("statuses", StoreStatus.values());
        model.addAttribute("stores", storeNames.list(filter));
        model.addAttribute("subscriptions", subscriptions.list(null).stream()
                .collect(Collectors.toMap(SubscriptionView::storeId, Function.identity())));
        return "platformadmin/stores/list";
    }

    @GetMapping("/{id}")
    String detail(@PathVariable UUID id, @RequestParam(required = false) String done,
            @RequestParam(required = false) String error, Model model) {
        var store = stores.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("store", store);
        model.addAttribute("subscription", subscriptions.forStore(id).orElse(null));
        model.addAttribute("plans", plans.all().stream().filter(PlanView::active).toList());
        model.addAttribute("invoices", invoices.ofStore(id));
        model.addAttribute("done", done != null && DONE.contains(done) ? "platform.store.done." + done : null);
        model.addAttribute("error", error != null && ERRORS.contains(error) ? "platform.store.error." + error : null);
        return "platformadmin/stores/detail";
    }

    @PostMapping("/{id}/suspend")
    String suspend(@PathVariable UUID id, @RequestParam(required = false) String reason) {
        return outcome(id, stores.suspend(id, reason), "suspended");
    }

    @PostMapping("/{id}/activate")
    String activate(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean screened,
            @RequestParam(required = false) String notes, Principal admin) {
        return outcome(id, stores.activate(id, screened, notes, admin.getName()), "activated");
    }

    @PostMapping("/{id}/plan")
    String changePlan(@PathVariable UUID id, @RequestParam(required = false) String plan) {
        return redirect(id, subscriptions.changePlan(id, plan) ? "done=plan" : "error=plan");
    }

    private String outcome(UUID id, StoreAdministration.StatusChange change, String done) {
        return switch (change) {
            case Changed changed -> redirect(id, "done=" + done);
            case ScreeningRequired required -> redirect(id, "error=screening");
            case ReasonRequired required -> redirect(id, "error=reason");
            case NotApplicable notApplicable -> {
                if (stores.find(id).isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                }
                yield redirect(id, "error=status");
            }
        };
    }

    private static String redirect(UUID id, String query) {
        return "redirect:/platform/stores/" + id + "?" + query;
    }
}
