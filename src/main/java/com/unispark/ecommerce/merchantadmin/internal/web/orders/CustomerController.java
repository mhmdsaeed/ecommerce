package com.unispark.ecommerce.merchantadmin.internal.web.orders;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import com.unispark.ecommerce.customer.Customers;
import com.unispark.ecommerce.shared.Htmx;
import com.unispark.ecommerce.shared.ListParams;

/**
 * Customers (SPEC 9.3): who ordered, how many orders they received or refused, and the blocklist (SPEC 6.4). The
 * search swaps the results over HTMX. All staff roles see customers; blocking is for owners and managers.
 */
@Controller
@RequestMapping("/admin/customers")
class CustomerController {

    static final String PAGE = "merchantadmin/customers";
    static final String RESULTS = PAGE + " :: results";

    private final Customers customers;

    CustomerController(Customers customers) {
        this.customers = customers;
    }

    @GetMapping
    String list(@RequestParam(required = false) String q, @RequestParam(name = "page", required = false) String page,
            Model model, HttpServletRequest request) {
        model.addAttribute("q", q);
        model.addAttribute("customers", customers.search(q, ListParams.page(page)));
        return Htmx.isHtmx(request) ? RESULTS : PAGE;
    }

    /** The customer's orders: the order list searched by their phone. */
    @GetMapping("/{id}/orders")
    String orders(@PathVariable UUID id) {
        var customer = customers.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return "redirect:/admin/orders?view=all&q=" + customer.phone().replace("+", "%2B");
    }

    @PostMapping("/{id}/block")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    String block(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean blocked,
            @RequestParam(required = false) String reason, @RequestParam(required = false) String q, Model model,
            HttpServletRequest request) {
        var found = blocked ? customers.block(id, reason) : customers.unblock(id);
        if (!found) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!Htmx.isHtmx(request)) {
            return "redirect:/admin/customers";
        }
        model.addAttribute("q", q);
        model.addAttribute("customers", customers.search(q, 0));
        return RESULTS;
    }
}
