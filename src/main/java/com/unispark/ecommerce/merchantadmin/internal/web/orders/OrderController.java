package com.unispark.ecommerce.merchantadmin.internal.web.orders;

import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import com.unispark.ecommerce.customer.Customers;
import com.unispark.ecommerce.identity.StaffDirectory;
import com.unispark.ecommerce.identity.StaffDirectory.StaffCard;
import com.unispark.ecommerce.identity.StaffPrincipal;
import com.unispark.ecommerce.media.ImageStore;
import com.unispark.ecommerce.notification.NotificationLog;
import com.unispark.ecommerce.order.Actor;
import com.unispark.ecommerce.order.IllegalOrderTransitionException;
import com.unispark.ecommerce.order.OrderStatus;
import com.unispark.ecommerce.order.Orders;
import com.unispark.ecommerce.order.Orders.DriverAssigned;
import com.unispark.ecommerce.order.Orders.NotADriver;
import com.unispark.ecommerce.order.Orders.NothingToReview;
import com.unispark.ecommerce.order.Orders.OrderClosed;
import com.unispark.ecommerce.order.Orders.OrderFilter;
import com.unispark.ecommerce.order.Orders.OrderNotFound;
import com.unispark.ecommerce.order.Orders.OrderView;
import com.unispark.ecommerce.order.Orders.ProofReviewed;
import com.unispark.ecommerce.payment.PaymentStatus;
import com.unispark.ecommerce.payment.Payments;
import com.unispark.ecommerce.shared.Htmx;
import com.unispark.ecommerce.shared.ListParams;

/**
 * Orders (SPEC 9.3). The list filters by status, payment and a search (number, phone or name) over HTMX, with the
 * filters in the URL. The detail page moves the order through its statuses, assigns a driver, decides on payment
 * proofs and can block the customer; each action is a plain form with post/redirect/get, so the page always shows
 * the order as it now is, history included.
 *
 * <p>All staff roles handle orders; blocking customers is for owners and managers.
 */
@Controller
@RequestMapping("/admin/orders")
class OrderController {

    static final String LIST_PAGE = "merchantadmin/orders/list";
    static final String LIST_RESULTS = LIST_PAGE + " :: results";
    static final String DETAIL_PAGE = "merchantadmin/orders/detail";
    static final Duration SCREENSHOT_LINK = Duration.ofMinutes(10);

    /** The list's status filter: everything, still open, or one status. */
    static final String VIEW_ALL = "all";
    static final String VIEW_OPEN = "open";

    /** Outcomes the detail page announces after an action (?done=, ?error=); anything else is ignored. */
    static final Set<String> DONE = Set.of("CONFIRMED", "PREPARING", "OUT_FOR_DELIVERY", "DELIVERED", "COMPLETED",
            "CANCELLED", "REFUSED", "RETURNED", "driver", "approved", "rejected", "blocked", "unblocked");
    static final Set<String> ERRORS = Set.of("changed", "driver", "closed");

    private final Orders orders;
    private final Payments payments;
    private final Customers customers;
    private final StaffDirectory staff;
    private final ImageStore images;
    private final NotificationLog notifications;

    OrderController(Orders orders, Payments payments, Customers customers, StaffDirectory staff, ImageStore images,
            NotificationLog notifications) {
        this.orders = orders;
        this.payments = payments;
        this.customers = customers;
        this.staff = staff;
        this.images = images;
        this.notifications = notifications;
    }

    /** Filters come from URLs people edit and share: unknown values are ignored (see {@link ListParams}). */
    @GetMapping
    String list(@RequestParam(defaultValue = VIEW_OPEN) String view,
            @RequestParam(name = "payment", required = false) String paymentParam,
            @RequestParam(required = false) String q, @RequestParam(name = "page", required = false) String pageParam,
            Model model, HttpServletRequest request) {
        var payment = ListParams.constant(PaymentStatus.class, paymentParam).orElse(null);
        var page = ListParams.page(pageParam);
        model.addAttribute("view", view);
        model.addAttribute("payment", payment);
        model.addAttribute("q", q);
        // The tabs: the open statuses in their order, counted, then the closed ones.
        var counts = orders.countsByStatus();
        model.addAttribute("counts", counts);
        model.addAttribute("openCount", counts.entrySet().stream()
                .filter(entry -> entry.getKey().isOpen()).mapToLong(Map.Entry::getValue).sum());
        model.addAttribute("openStatuses", Arrays.stream(OrderStatus.values()).filter(OrderStatus::isOpen).toList());
        model.addAttribute("closedStatuses", Arrays.stream(OrderStatus.values()).filter(status -> !status.isOpen()).toList());
        model.addAttribute("paymentStatuses", PaymentStatus.values());
        model.addAttribute("orders", orders.list(new OrderFilter(statuses(view), payment, q), page));
        model.addAttribute("staffNames", names());
        return Htmx.isHtmx(request) ? LIST_RESULTS : LIST_PAGE;
    }

    @GetMapping("/{number}")
    String detail(@PathVariable int number, @RequestParam(required = false) String done,
            @RequestParam(required = false) String error, Model model) {
        var order = find(number);
        model.addAttribute("done", done != null && DONE.contains(done) ? "admin.orders.done." + done : null);
        model.addAttribute("error", error != null && ERRORS.contains(error) ? "admin.orders.error." + error : null);
        model.addAttribute("order", order);
        model.addAttribute("payment", payments.instructions(order.paymentMethod(), order.total()));
        model.addAttribute("customer", customers.find(order.customerId()).orElse(null));
        model.addAttribute("drivers", staff.activeDrivers());
        model.addAttribute("staffNames", names());
        model.addAttribute("actors", actorLabels(order));
        model.addAttribute("messages", notifications.forOrder(order.id()));
        var links = new HashMap<UUID, String>();
        order.proofs().stream().filter(proof -> proof.imageKey() != null)
                .forEach(proof -> links.put(proof.id(), images.signedUrl(proof.imageKey(), SCREENSHOT_LINK)));
        model.addAttribute("screenshots", links);
        return DETAIL_PAGE;
    }

    @PostMapping("/{number}/status")
    String changeStatus(@PathVariable int number, @RequestParam OrderStatus target,
            @RequestParam(required = false) String note, @AuthenticationPrincipal StaffPrincipal actor) {
        var order = find(number);
        try {
            orders.changeStatus(order.id(), target, Actor.staff(actor.staffId()), note);
        } catch (IllegalOrderTransitionException e) {
            // Someone else moved the order first (another tab, another staff member): show it as it is now.
            return redirect(number, "error=changed");
        }
        return redirect(number, "done=" + target.name());
    }

    @PostMapping("/{number}/driver")
    String assignDriver(@PathVariable int number, @RequestParam(required = false) UUID driverId,
            @AuthenticationPrincipal StaffPrincipal actor) {
        var order = find(number);
        return switch (orders.assignDriver(order.id(), driverId, Actor.staff(actor.staffId()))) {
            case DriverAssigned assigned -> redirect(number, "done=driver");
            case NotADriver notADriver -> redirect(number, "error=driver");
            case OrderClosed closed -> redirect(number, "error=closed");
            case OrderNotFound notFound -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
    }

    @PostMapping("/{number}/proof")
    String reviewProof(@PathVariable int number, @RequestParam String decision,
            @RequestParam(required = false) String note, @AuthenticationPrincipal StaffPrincipal actor) {
        var order = find(number);
        var approve = "approve".equals(decision);
        return switch (orders.reviewProof(order.id(), approve, note, Actor.staff(actor.staffId()))) {
            case ProofReviewed reviewed -> redirect(number, approve ? "done=approved" : "done=rejected");
            case NothingToReview nothing -> redirect(number, "error=changed");
            case OrderNotFound notFound -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
    }

    @PostMapping("/{number}/block")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    String block(@PathVariable int number, @RequestParam(defaultValue = "false") boolean blocked,
            @RequestParam(required = false) String reason) {
        var order = find(number);
        if (blocked) {
            customers.block(order.customerId(), reason);
        } else {
            customers.unblock(order.customerId());
        }
        return redirect(number, blocked ? "done=blocked" : "done=unblocked");
    }

    private OrderView find(int number) {
        return orders.findByNumber(number).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static String redirect(int number, String query) {
        return "redirect:/admin/orders/" + number + "?" + query;
    }

    /** The status filter of the list: open orders by default, all, or one status. */
    static Set<OrderStatus> statuses(String view) {
        if (VIEW_ALL.equals(view)) {
            return Set.of();
        }
        if (VIEW_OPEN.equals(view)) {
            return Arrays.stream(OrderStatus.values()).filter(OrderStatus::isOpen)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(OrderStatus.class)));
        }
        for (var status : OrderStatus.values()) {
            if (status.name().equals(view)) {
                return EnumSet.of(status);
            }
        }
        return Set.of();
    }

    private Map<UUID, String> names() {
        return staff.all().stream().collect(Collectors.toMap(StaffCard::id, StaffCard::name));
    }

    /**
     * How each actor of the history reads: a staff member's name, or a message key for the customer and the system.
     */
    private Map<String, String> actorLabels(OrderView order) {
        var names = names();
        var labels = new HashMap<String, String>();
        for (var event : order.events()) {
            var actor = event.actor();
            if (actor.startsWith("staff:")) {
                var id = UUID.fromString(actor.substring("staff:".length()));
                labels.put(actor, names.getOrDefault(id, "?"));
            }
        }
        return labels;
    }
}
