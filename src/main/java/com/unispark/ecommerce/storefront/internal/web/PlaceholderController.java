package com.unispark.ecommerce.storefront.internal.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreLookup;

/**
 * Placeholder home page. On a store subdomain it greets the shopper with the store name. Replaced by the
 * storefront home in M5.
 */
@Controller
class PlaceholderController {

    private final StoreLookup stores;

    PlaceholderController(StoreLookup stores) {
        this.stores = stores;
    }

    @GetMapping("/")
    String home(Model model) {
        TenantContext.currentTenantId()
                .flatMap(stores::findById)
                .ifPresent(store -> model.addAttribute("store", store));
        return "storefront/placeholder";
    }
}
