package com.unispark.ecommerce.storefront.internal.web;

import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import com.unispark.ecommerce.customer.PhoneVerification;
import com.unispark.ecommerce.customer.PhoneVerification.InvalidPhone;
import com.unispark.ecommerce.customer.PhoneVerification.NoCode;
import com.unispark.ecommerce.customer.PhoneVerification.Sent;
import com.unispark.ecommerce.customer.PhoneVerification.TooManyCodes;
import com.unispark.ecommerce.customer.PhoneVerification.Verified;
import com.unispark.ecommerce.customer.PhoneVerification.WrongCode;
import com.unispark.ecommerce.delivery.DeliveryZones;
import com.unispark.ecommerce.order.Checkout;
import com.unispark.ecommerce.order.Checkout.Placed;
import com.unispark.ecommerce.order.Checkout.Rejected;
import com.unispark.ecommerce.payment.PaymentMethods;
import com.unispark.ecommerce.shared.Htmx;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreSettings;

/**
 * Checkout (SPEC 9.2). The page is a plain form posted with its CSRF token (the page is never cached, so the token
 * may be in it), so placing an order works without JavaScript. With JavaScript, changing the address refreshes the
 * summary and its fee, and the phone code is sent and checked over HTMX.
 *
 * <p>A successful order redirects to its page; a refused one shows the form again with the reasons. The form keeps
 * its idempotency key, so submitting twice places one order.
 */
@Controller
class CheckoutController {

    static final String PAGE = "storefront/checkout";
    static final String SUMMARY = "storefront/fragments/checkout :: summary-update";
    static final String PHONE = "storefront/fragments/checkout :: phone-check";

    private final Checkout checkout;
    private final PaymentMethods methods;
    private final PhoneVerification verification;
    private final StoreSettings settings;
    private final DeliveryZones zones;
    private final CartCookies cookies;

    CheckoutController(Checkout checkout, PaymentMethods methods, PhoneVerification verification,
            StoreSettings settings, DeliveryZones zones, CartCookies cookies) {
        this.checkout = checkout;
        this.methods = methods;
        this.verification = verification;
        this.settings = settings;
        this.zones = zones;
        this.cookies = cookies;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        if (TenantContext.currentTenantId().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @GetMapping("/checkout")
    String page(Model model, HttpServletRequest request) {
        var form = CheckoutForm.start(CartCookies.readDestination(request).orElse(null));
        if (!render(form, model, request)) {
            return "redirect:/cart";
        }
        return PAGE;
    }

    /** The order summary for the address typed so far; remembers it for the cart's delivery box too. */
    @GetMapping("/checkout/summary")
    String summary(@ModelAttribute("form") CheckoutForm form, BindingResult binding, Model model,
            HttpServletRequest request, HttpServletResponse response) {
        if (binding.hasErrors()) {
            form.setGovernorate(null);
        }
        if (form.destination() != null) {
            cookies.writeDestination(response, form.destination());
        }
        if (!Htmx.isHtmx(request)) {
            return "redirect:/checkout";
        }
        model.addAttribute("preview", checkout.preview(CartCookies.token(request), form.destination()));
        model.addAttribute("coverage", zones.coverage());
        return SUMMARY;
    }

    @PostMapping("/checkout")
    String place(@ModelAttribute("form") CheckoutForm form, BindingResult binding, Model model,
            HttpServletRequest request, HttpServletResponse response) {
        if (!binding.hasErrors()) {
            var result = checkout.place(form.toRequest(CartCookies.token(request), CartCookies.verification(request)));
            if (result instanceof Placed placed) {
                cookies.writeCount(response, 0);
                return "redirect:" + UriComponentsBuilder.fromPath("/orders/{number}")
                        .queryParam("t", placed.trackingToken()).buildAndExpand(placed.number()).toUriString();
            }
            var rejected = (Rejected) result;
            for (var problem : rejected.problems()) {
                binding.rejectValue(problem.field(), "checkout." + problem.field() + "." + problem.code());
            }
            model.addAttribute("refusal", rejected.refusal());
        }
        if (!render(form, model, request)) {
            return "redirect:/cart";
        }
        return PAGE;
    }

    @PostMapping("/checkout/code")
    String sendCode(@RequestParam(defaultValue = "") String phone, Model model, Locale locale) {
        var result = verification.send(phone, locale);
        switch (result) {
            case Sent sent -> model.addAttribute("phoneState", "sent");
            case InvalidPhone invalid -> model.addAttribute("phoneState", "invalid");
            case TooManyCodes tooMany -> model.addAttribute("phoneState", "too-many");
        }
        return PHONE;
    }

    @PostMapping("/checkout/verify")
    String verify(@RequestParam(defaultValue = "") String phone, @RequestParam(defaultValue = "") String code,
            Model model, HttpServletResponse response) {
        switch (verification.verify(phone, code)) {
            case Verified verified -> {
                cookies.writeVerification(response, verified.token());
                model.addAttribute("phoneState", "verified");
            }
            case WrongCode wrong -> {
                model.addAttribute("phoneState", "wrong");
                model.addAttribute("attemptsLeft", wrong.attemptsLeft());
            }
            case NoCode none -> model.addAttribute("phoneState", "expired");
        }
        return PHONE;
    }

    /** Fills the page model; false when the cart is empty, so there is nothing to check out. */
    private boolean render(CheckoutForm form, Model model, HttpServletRequest request) {
        var preview = checkout.preview(CartCookies.token(request), form.destination());
        if (preview.cart().isEmpty()) {
            return false;
        }
        var store = settings.current();
        var enabled = methods.enabled();
        if (form.getPaymentMethod() == null && enabled.size() == 1) {
            form.setPaymentMethod(enabled.getFirst().method());
        }
        model.addAttribute("form", form);
        model.addAttribute("preview", preview);
        model.addAttribute("coverage", zones.coverage());
        model.addAttribute("methods", enabled);
        model.addAttribute("otpRequired", store.otpRequired());
        if (store.otpRequired() && form.getPhone() != null
                && verification.isVerified(form.getPhone(), CartCookies.verification(request))) {
            model.addAttribute("phoneState", "verified");
        }
        return true;
    }
}
