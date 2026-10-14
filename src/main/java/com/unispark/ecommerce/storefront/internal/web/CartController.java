package com.unispark.ecommerce.storefront.internal.web;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import com.unispark.ecommerce.cart.Carts;
import com.unispark.ecommerce.cart.Carts.CartView;
import com.unispark.ecommerce.cart.Carts.Change;
import com.unispark.ecommerce.delivery.Destination;
import com.unispark.ecommerce.delivery.Governorate;
import com.unispark.ecommerce.shared.Htmx;
import com.unispark.ecommerce.shared.TenantContext;

/**
 * The cart (SPEC 9.2). Changes come over HTMX: adding answers with a short status fragment, changing a line with
 * the whole cart fragment. Each change triggers cart-changed, which the header badge listens for. Never cached.
 *
 * <p>Under the lines, the delivery box quotes the fee for the destination the shopper picks (SPEC 8). Picking is a
 * GET, so it needs no CSRF token; the choice is kept in a cookie so every later render of the cart shows it.
 */
@Controller
class CartController {

    static final String PAGE = "storefront/cart";
    static final String CART = "storefront/fragments/cart :: cart";
    static final String ADDED = "storefront/fragments/cart :: added";
    static final String DELIVERY = "storefront/fragments/cart :: delivery-update";
    static final String CHANGED_EVENT = "cart-changed";

    private final Carts carts;
    private final CartCookies cookies;
    private final DeliveryEstimates estimates;

    CartController(Carts carts, CartCookies cookies, DeliveryEstimates estimates) {
        this.carts = carts;
        this.cookies = cookies;
        this.estimates = estimates;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        if (TenantContext.currentTenantId().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @GetMapping("/cart")
    String cart(Model model, HttpServletRequest request, HttpServletResponse response) {
        var cart = carts.view(CartCookies.token(request));
        // Corrects the badge if the cart changed elsewhere or expired.
        cookies.writeCount(response, cart.itemCount());
        addCart(model, cart, request);
        return Htmx.isHtmx(request) ? CART : PAGE;
    }

    @GetMapping("/cart/delivery")
    String delivery(@RequestParam(required = false) String governorate, @RequestParam(required = false) String city,
            @RequestParam(required = false) String area, Model model,
            HttpServletRequest request, HttpServletResponse response) {
        var destination = parse(governorate, city, area);
        if (destination != null) {
            cookies.writeDestination(response, destination);
        }
        if (!Htmx.isHtmx(request)) {
            return "redirect:/cart";
        }
        model.addAttribute("delivery", estimates.estimate(carts.view(CartCookies.token(request)), destination));
        return DELIVERY;
    }

    @PostMapping("/cart/items")
    String add(@RequestParam UUID variantId, @RequestParam(defaultValue = "1") int quantity, Model model,
            HttpServletRequest request, HttpServletResponse response) {
        var outcome = carts.add(CartCookies.token(request), variantId, quantity);
        if (outcome.token() != null) {
            cookies.write(response, outcome.token(), outcome.itemCount());
        }
        response.setHeader(Htmx.TRIGGER, CHANGED_EVENT);
        if (!Htmx.isHtmx(request)) {
            return "redirect:/cart";
        }
        model.addAttribute("notice", outcome.notice());
        return ADDED;
    }

    @PatchMapping("/cart/items/{itemId}")
    String update(@PathVariable UUID itemId, @RequestParam int quantity, Model model,
            HttpServletRequest request, HttpServletResponse response) {
        return changed(carts.update(CartCookies.token(request), itemId, quantity), model, request, response);
    }

    @DeleteMapping("/cart/items/{itemId}")
    String remove(@PathVariable UUID itemId, Model model, HttpServletRequest request, HttpServletResponse response) {
        return changed(carts.remove(CartCookies.token(request), itemId), model, request, response);
    }

    private String changed(Change change, Model model, HttpServletRequest request, HttpServletResponse response) {
        cookies.writeCount(response, change.cart().itemCount());
        response.setHeader(Htmx.TRIGGER, CHANGED_EVENT);
        if (!Htmx.isHtmx(request)) {
            return "redirect:/cart";
        }
        addCart(model, change.cart(), request);
        model.addAttribute("notice", change.notice());
        return CART;
    }

    private void addCart(Model model, CartView cart, HttpServletRequest request) {
        model.addAttribute("cart", cart);
        model.addAttribute("delivery",
                estimates.estimate(cart, CartCookies.readDestination(request).orElse(null)));
    }

    /** Null without a valid governorate: the box then asks for one again. */
    private static Destination parse(String governorate, String city, String area) {
        if (governorate == null || governorate.isBlank()) {
            return null;
        }
        try {
            return Destination.of(Governorate.valueOf(governorate), truncate(city), truncate(area));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Longer than any zone name, so it cannot match; cut so the cookie stays small. */
    private static String truncate(String name) {
        return name == null || name.length() <= 100 ? name : name.substring(0, 100);
    }
}
