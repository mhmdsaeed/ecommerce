package com.unispark.ecommerce.merchantadmin.internal.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.unispark.ecommerce.identity.StaffPrincipal;
import com.unispark.ecommerce.tenant.Sites;

/** Merchant admin home. Orders, verifications and COD totals arrive with their milestones (SPEC 9.3). */
@Controller
class DashboardController {

    private final Sites sites;

    DashboardController(Sites sites) {
        this.sites = sites;
    }

    @GetMapping("/admin")
    String dashboard(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, Model model) {
        var storefrontHost = sites.storeHost(staff.storeSlug());
        model.addAttribute("storefrontHost", storefrontHost);
        model.addAttribute("storefrontUrl", request.getScheme() + "://" + storefrontHost + "/");
        return "merchantadmin/dashboard";
    }
}
