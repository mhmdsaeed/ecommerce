package com.unispark.ecommerce.merchantadmin.internal.web.driver;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import com.unispark.ecommerce.identity.StaffPrincipal;
import com.unispark.ecommerce.order.CodSettlement;
import com.unispark.ecommerce.order.DriverDeliveries;
import com.unispark.ecommerce.order.DriverDeliveries.AwaitingTransfer;
import com.unispark.ecommerce.order.DriverDeliveries.Done;
import com.unispark.ecommerce.order.DriverDeliveries.DriverResult;
import com.unispark.ecommerce.order.DriverDeliveries.InvalidAmount;
import com.unispark.ecommerce.order.DriverDeliveries.NotYours;
import com.unispark.ecommerce.order.DriverDeliveries.WrongStep;
import com.unispark.ecommerce.order.Orders.OrderView;

/**
 * The driver view (SPEC 8), phone first: the driver's open orders and the cash they hold; per order, call and
 * WhatsApp buttons, a map search for the address, and the delivery steps. Steps are plain forms with
 * post/redirect/get, so a flaky connection or a double tap never moves an order twice.
 */
@Controller
@RequestMapping("/driver")
class DriverController {

    /** Outcomes the pages announce (?done=, ?error=); anything else in the URL is ignored. */
    static final Set<String> DONE = Set.of("started", "delivered", "refused");
    static final Set<String> ERRORS = Set.of("step", "amount", "unpaid");

    private final DriverDeliveries deliveries;
    private final CodSettlement settlement;
    private final MessageSource messages;

    DriverController(DriverDeliveries deliveries, CodSettlement settlement, MessageSource messages) {
        this.deliveries = deliveries;
        this.settlement = settlement;
        this.messages = messages;
    }

    @GetMapping
    String home(@AuthenticationPrincipal StaffPrincipal driver, @RequestParam(required = false) String done,
            Model model) {
        model.addAttribute("orders", deliveries.openFor(driver.staffId()));
        model.addAttribute("cash", settlement.statement(driver.staffId()).balances());
        model.addAttribute("done", done != null && DONE.contains(done) ? "driver.done." + done : null);
        return "driver/home";
    }

    @GetMapping("/orders/{number}")
    String order(@PathVariable int number, @AuthenticationPrincipal StaffPrincipal driver,
            @RequestParam(required = false) String done, @RequestParam(required = false) String error, Model model,
            Locale locale) {
        var order = deliveries.find(driver.staffId(), number)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("order", order);
        model.addAttribute("mapUrl", mapUrl(order, locale));
        model.addAttribute("whatsapp", "https://wa.me/" + order.customerPhone().replace("+", ""));
        model.addAttribute("done", done != null && DONE.contains(done) ? "driver.done." + done : null);
        model.addAttribute("error", error != null && ERRORS.contains(error) ? "driver.error." + error : null);
        return "driver/order";
    }

    @PostMapping("/orders/{number}/start")
    String start(@PathVariable int number, @AuthenticationPrincipal StaffPrincipal driver) {
        return after(number, deliveries.startDelivery(driver.staffId(), number), "started");
    }

    @PostMapping("/orders/{number}/deliver")
    String deliver(@PathVariable int number, @RequestParam(defaultValue = "") String collected,
            @AuthenticationPrincipal StaffPrincipal driver) {
        var result = deliveries.deliver(driver.staffId(), number, collected);
        // Delivered orders leave the driver's list: back to it, with the confirmation.
        return result instanceof Done ? "redirect:/driver?done=delivered" : after(number, result, "delivered");
    }

    @PostMapping("/orders/{number}/refuse")
    String refuse(@PathVariable int number, @RequestParam(required = false) String reason,
            @AuthenticationPrincipal StaffPrincipal driver) {
        var result = deliveries.refuse(driver.staffId(), number, reason);
        return result instanceof Done ? "redirect:/driver?done=refused" : after(number, result, "refused");
    }

    private static String after(int number, DriverResult result, String done) {
        return switch (result) {
            case Done ok -> "redirect:/driver/orders/" + number + "?done=" + done;
            case WrongStep wrong -> "redirect:/driver/orders/" + number + "?error=step";
            case InvalidAmount invalid -> "redirect:/driver/orders/" + number + "?error=amount";
            case AwaitingTransfer unpaid -> "redirect:/driver/orders/" + number + "?error=unpaid";
            case NotYours notYours -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
    }

    /** A map search for the address as written; there are no coordinates in an order's address yet. */
    private String mapUrl(OrderView order, Locale locale) {
        var address = order.address();
        var parts = new StringBuilder(messages.getMessage("governorate." + address.governorate(), null, locale))
                .append(", ").append(address.city());
        if (address.area() != null) {
            parts.append(", ").append(address.area());
        }
        if (address.landmark() != null) {
            parts.append(", ").append(address.landmark());
        }
        return "https://www.google.com/maps/search/?api=1&query="
                + URLEncoder.encode(parts.toString(), StandardCharsets.UTF_8);
    }
}
