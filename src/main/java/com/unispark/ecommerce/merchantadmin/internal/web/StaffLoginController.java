package com.unispark.ecommerce.merchantadmin.internal.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.unispark.ecommerce.tenant.Sites;
import com.unispark.ecommerce.tenant.StoreSlugs;

/**
 * Login pages for the merchant admin and the driver view. Both post to their own chain, and either one sends
 * staff to the area that matches their role. The store address comes from the last failed attempt or from the
 * cookie set at the last successful login.
 */
@Controller
class StaffLoginController {

    static final String VIEW = "merchantadmin/login";

    private final Sites sites;

    StaffLoginController(Sites sites) {
        this.sites = sites;
    }

    @GetMapping("/admin/login")
    String adminLogin(@RequestParam(name = "store", required = false) String store,
            @CookieValue(name = "store", required = false) String rememberedStore, Model model) {
        return page("admin", store, rememberedStore, model);
    }

    @GetMapping("/driver/login")
    String driverLogin(@RequestParam(name = "store", required = false) String store,
            @CookieValue(name = "store", required = false) String rememberedStore, Model model) {
        return page("driver", store, rememberedStore, model);
    }

    private String page(String area, String store, String rememberedStore, Model model) {
        var prefill = StoreSlugs.normalize(store != null ? store : rememberedStore);
        model.addAttribute("area", area);
        model.addAttribute("layout", "driver".equals(area) ? "layouts/driver" : "layouts/admin");
        model.addAttribute("loginAction", "/" + area + "/login");
        model.addAttribute("store", StoreSlugs.hasValidFormat(prefill) ? prefill : "");
        model.addAttribute("storeDomain", "." + sites.baseHost());
        return VIEW;
    }
}
