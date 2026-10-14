package com.unispark.ecommerce.merchantadmin.internal.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.unispark.ecommerce.identity.StaffPrincipal;
import com.unispark.ecommerce.pricing.ExchangeRates;
import com.unispark.ecommerce.tenant.Sites;

/** Merchant admin home. Orders, verifications and COD totals arrive with their milestones (SPEC 9.3). */
@Controller
class DashboardController {

    private final Sites sites;
    private final ExchangeRates rates;

    DashboardController(Sites sites, ExchangeRates rates) {
        this.sites = sites;
        this.rates = rates;
    }

    @GetMapping("/admin")
    String dashboard(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, Model model) {
        var storefrontHost = sites.storeHost(staff.storeSlug());
        model.addAttribute("storefrontHost", storefrontHost);
        model.addAttribute("storefrontUrl", request.getScheme() + "://" + storefrontHost + "/");
        // Without a rate, prices in the other currency cannot be converted for shoppers.
        model.addAttribute("rateMissing", staff.role().managesStaff() && rates.current().isEmpty());
        return "merchantadmin/dashboard";
    }
}
