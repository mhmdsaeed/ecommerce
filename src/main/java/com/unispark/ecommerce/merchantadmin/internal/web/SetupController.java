package com.unispark.ecommerce.merchantadmin.internal.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.unispark.ecommerce.identity.StaffPrincipal;
import com.unispark.ecommerce.tenant.Sites;
import com.unispark.ecommerce.tenant.StoreSettings;

/**
 * The setup wizard (SPEC 9.3): where a new merchant lands after signup. Each step links to the page that does it
 * and is ticked once done; the last step opens the store to shoppers. Until then the store can be previewed
 * through a private link. Owners and managers only.
 */
@Controller
@RequestMapping("/admin/setup")
@PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
class SetupController {

    private final SetupChecklist checklist;
    private final StoreSettings settings;
    private final Sites sites;

    SetupController(SetupChecklist checklist, StoreSettings settings, Sites sites) {
        this.checklist = checklist;
        this.settings = settings;
        this.sites = sites;
    }

    @GetMapping
    String setup(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, Model model) {
        var current = checklist.current();
        var storefront = request.getScheme() + "://" + sites.storeHost(staff.storeSlug()) + "/";
        model.addAttribute("checklist", current);
        model.addAttribute("storefrontUrl", storefront);
        model.addAttribute("previewUrl", current.launched() ? null : storefront + "?preview=" + settings.previewToken());
        return "merchantadmin/setup";
    }

    @PostMapping("/launch")
    String launch() {
        if (!checklist.current().readyToLaunch()) {
            return "redirect:/admin/setup?not-ready";
        }
        settings.launch();
        return "redirect:/admin/setup?launched";
    }
}
