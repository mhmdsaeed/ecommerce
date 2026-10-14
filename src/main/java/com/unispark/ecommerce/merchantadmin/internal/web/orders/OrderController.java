package com.unispark.ecommerce.merchantadmin.internal.web.orders;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import com.unispark.ecommerce.order.Orders;
import com.unispark.ecommerce.payment.Payments;

/**
 * Orders (SPEC 9.3): the newest first, and each order's details with its event log. Read-only for now: confirming,
 * cancelling, payment checks and drivers come with order management (M9). All staff roles.
 */
@Controller
@RequestMapping("/admin/orders")
class OrderController {

    private final Orders orders;
    private final Payments payments;

    OrderController(Orders orders, Payments payments) {
        this.orders = orders;
        this.payments = payments;
    }

    @GetMapping
    String list(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("orders", orders.list(page));
        return "merchantadmin/orders/list";
    }

    @GetMapping("/{number}")
    String detail(@PathVariable int number, Model model) {
        var order = orders.findByNumber(number).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("order", order);
        model.addAttribute("payment", payments.instructions(order.paymentMethod(), order.total()));
        return "merchantadmin/orders/detail";
    }
}
