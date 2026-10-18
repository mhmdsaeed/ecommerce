package com.unispark.ecommerce.merchantadmin.internal.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.unispark.ecommerce.billing.Plans;
import com.unispark.ecommerce.billing.Plans.PlanView;
import com.unispark.ecommerce.payment.PaymentMethod;
import com.unispark.ecommerce.tenant.Sites;

/**
 * The main site's landing page, for merchants deciding whether to open a store. {@code /} on the main site
 * forwards here (the storefront owns {@code /}); store hosts deny the path. The address field opens the signup
 * form with that address filled in. Prices are the active plans, as the platform admin set them.
 */
@Controller
class LandingController {

    static final String VIEW = "merchantadmin/landing";

    private final Plans plans;
    private final Sites sites;

    LandingController(Plans plans, Sites sites) {
        this.plans = plans;
        this.sites = sites;
    }

    @GetMapping("/welcome")
    String landing(Model model) {
        model.addAttribute("storeDomain", "." + sites.baseHost());
        model.addAttribute("plans", plans.all().stream().filter(PlanView::active).toList());
        model.addAttribute("trialDays", plans.trialDays());
        model.addAttribute("paymentMethods", PaymentMethod.values());
        return VIEW;
    }
}
