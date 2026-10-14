package com.unispark.ecommerce.merchantadmin.internal.web.settings;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.unispark.ecommerce.order.Checkout;
import com.unispark.ecommerce.shared.Amounts;
import com.unispark.ecommerce.tenant.StoreSettings;

/**
 * Store settings: checkout (SPEC 6.4). Phone verification by code, and the largest order a new customer may place.
 * Owners and managers only.
 */
@Controller
@RequestMapping("/admin/settings/checkout")
@PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
class CheckoutSettingsController {

    static final String PAGE = "merchantadmin/settings/checkout";

    private final StoreSettings settings;

    CheckoutSettingsController(StoreSettings settings) {
        this.settings = settings;
    }

    @ModelAttribute("maxOpenOrders")
    int maxOpenOrders() {
        return Checkout.MAX_OPEN_ORDERS_FOR_NEW_CUSTOMERS;
    }

    @GetMapping
    String page(Model model) {
        var current = settings.current();
        model.addAttribute("otpRequired", current.otpRequired());
        model.addAttribute("maxTotal", current.newCustomerMaxTotal() == null ? ""
                : current.newCustomerMaxTotal().stripTrailingZeros().toPlainString());
        model.addAttribute("currency", current.displayCurrency());
        return PAGE;
    }

    @PostMapping
    String save(@RequestParam(defaultValue = "false") boolean otpRequired,
            @RequestParam(defaultValue = "") String maxTotal, Model model) {
        var amount = maxTotal.isBlank() ? null : Amounts.parse(maxTotal).filter(value -> value.signum() > 0).orElse(null);
        if (!maxTotal.isBlank() && amount == null) {
            model.addAttribute("otpRequired", otpRequired);
            model.addAttribute("maxTotal", maxTotal);
            model.addAttribute("currency", settings.current().displayCurrency());
            model.addAttribute("maxTotalError", "settings.checkout.max-total.invalid");
            return PAGE;
        }
        settings.updateCheckout(otpRequired, amount);
        return "redirect:/admin/settings/checkout?saved";
    }
}
