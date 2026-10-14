package com.unispark.ecommerce.storefront.internal.web;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import com.unispark.ecommerce.cart.Carts;
import com.unispark.ecommerce.cart.Carts.Change;
import com.unispark.ecommerce.shared.Htmx;
import com.unispark.ecommerce.shared.TenantContext;

/**
 * The cart (SPEC 9.2). Changes come over HTMX: adding answers with a short status fragment, changing a line with
 * the whole cart fragment. Each change triggers cart-changed, which the header badge listens for. Never cached.
 */
@Controller
class CartController {

    static final String PAGE = "storefront/cart";
    static final String CART = "storefront/fragments/cart :: cart";
    static final String ADDED = "storefront/fragments/cart :: added";
    static final String CHANGED_EVENT = "cart-changed";

    private final Carts carts;
    private final CartCookies cookies;

    CartController(Carts carts, CartCookies cookies) {
        this.carts = carts;
        this.cookies = cookies;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        if (TenantContext.currentTenantId().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @GetMapping("/cart")
    String cart(@CookieValue(name = CartCookies.TOKEN, required = false) String token, Model model,
            HttpServletRequest request, HttpServletResponse response) {
        var cart = carts.view(token);
        // Corrects the badge if the cart changed elsewhere or expired.
        cookies.writeCount(response, cart.itemCount());
        model.addAttribute("cart", cart);
        return Htmx.isHtmx(request) ? CART : PAGE;
    }

    @PostMapping("/cart/items")
    String add(@RequestParam UUID variantId, @RequestParam(defaultValue = "1") int quantity,
            @CookieValue(name = CartCookies.TOKEN, required = false) String token, Model model,
            HttpServletRequest request, HttpServletResponse response) {
        var outcome = carts.add(token, variantId, quantity);
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
    String update(@PathVariable UUID itemId, @RequestParam int quantity,
            @CookieValue(name = CartCookies.TOKEN, required = false) String token, Model model,
            HttpServletRequest request, HttpServletResponse response) {
        return changed(carts.update(token, itemId, quantity), model, request, response);
    }

    @DeleteMapping("/cart/items/{itemId}")
    String remove(@PathVariable UUID itemId, @CookieValue(name = CartCookies.TOKEN, required = false) String token,
            Model model, HttpServletRequest request, HttpServletResponse response) {
        return changed(carts.remove(token, itemId), model, request, response);
    }

    private String changed(Change change, Model model, HttpServletRequest request, HttpServletResponse response) {
        cookies.writeCount(response, change.cart().itemCount());
        response.setHeader(Htmx.TRIGGER, CHANGED_EVENT);
        if (!Htmx.isHtmx(request)) {
            return "redirect:/cart";
        }
        model.addAttribute("cart", change.cart());
        model.addAttribute("notice", change.notice());
        return CART;
    }
}
