package com.unispark.ecommerce.merchantadmin.internal.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.unispark.ecommerce.identity.StaffPrincipal;
import com.unispark.ecommerce.order.CodSettlement;
import com.unispark.ecommerce.order.Orders;
import com.unispark.ecommerce.pricing.ExchangeRates;
import com.unispark.ecommerce.tenant.Sites;

/** Merchant admin home (SPEC 9.3): today's orders and what waits for the merchant. COD totals arrive with M10. */
@Controller
class DashboardController {

    static final ZoneId STORE_ZONE = ZoneId.of("Asia/Damascus");

    private final Sites sites;
    private final ExchangeRates rates;
    private final Orders orders;
    private final CodSettlement settlement;
    private final Clock clock;

    DashboardController(Sites sites, ExchangeRates rates, Orders orders, CodSettlement settlement, Clock clock) {
        this.sites = sites;
        this.rates = rates;
        this.orders = orders;
        this.settlement = settlement;
        this.clock = clock;
    }

    @GetMapping("/admin")
    String dashboard(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, Model model) {
        var storefrontHost = sites.storeHost(staff.storeSlug());
        model.addAttribute("storefrontHost", storefrontHost);
        model.addAttribute("storefrontUrl", request.getScheme() + "://" + storefrontHost + "/");
        // Without a rate, prices in the other currency cannot be converted for shoppers.
        model.addAttribute("rateMissing", staff.role().managesStaff() && rates.current().isEmpty());
        // "Today" as the store sees it: since midnight in Damascus (SPEC 11).
        var midnight = LocalDate.now(clock.withZone(STORE_ZONE)).atStartOfDay(STORE_ZONE).toInstant();
        model.addAttribute("counts", orders.counts(midnight));
        // The store's cash in drivers' hands: for owners and managers, who settle it.
        model.addAttribute("outstanding", staff.role().managesStaff() ? settlement.outstanding() : null);
        return "merchantadmin/dashboard";
    }
}
