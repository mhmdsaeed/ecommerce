package com.unispark.ecommerce.storefront.internal.web;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import com.unispark.ecommerce.order.Orders;
import com.unispark.ecommerce.order.Orders.InvalidReference;
import com.unispark.ecommerce.order.Orders.OrderNotFound;
import com.unispark.ecommerce.order.Orders.ProofAccepted;
import com.unispark.ecommerce.order.Orders.ProofNotExpected;
import com.unispark.ecommerce.payment.Payments;
import com.unispark.ecommerce.shared.TenantContext;

/**
 * The shopper's order page (SPEC 9.2): status, what was ordered, and how to pay. Only reachable with the order's
 * tracking token, given out when the order is placed. Never cached, and never sent as a referrer, since the URL is
 * the key to the order.
 */
@Controller
class OrderPageController {

    static final String PAGE = "storefront/order";

    private final Orders orders;
    private final Payments payments;

    OrderPageController(Orders orders, Payments payments) {
        this.orders = orders;
        this.payments = payments;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        if (TenantContext.currentTenantId().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    @GetMapping("/orders/{number}")
    String order(@PathVariable int number, @RequestParam(name = "t", defaultValue = "") String token, Model model) {
        var order = orders.track(number, token).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("order", order);
        model.addAttribute("token", token);
        model.addAttribute("payment", payments.instructions(order.paymentMethod(), order.total()));
        return PAGE;
    }

    @PostMapping("/orders/{number}/proof")
    String proof(@PathVariable int number, @RequestParam(name = "t", defaultValue = "") String token,
            @RequestParam(defaultValue = "") String reference) {
        var outcome = switch (orders.submitProof(number, token, reference)) {
            case ProofAccepted accepted -> "proof-sent";
            case InvalidReference invalid -> "proof-invalid";
            case ProofNotExpected notExpected -> "proof-not-expected";
            case OrderNotFound notFound -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
        return "redirect:" + UriComponentsBuilder.fromPath("/orders/{number}").queryParam("t", token)
                .queryParam(outcome).buildAndExpand(number).toUriString();
    }
}
