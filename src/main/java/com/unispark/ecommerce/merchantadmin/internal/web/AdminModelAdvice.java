package com.unispark.ecommerce.merchantadmin.internal.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.unispark.ecommerce.billing.SubscriptionStatus;
import com.unispark.ecommerce.billing.Subscriptions;
import com.unispark.ecommerce.identity.ColorSchemes;
import com.unispark.ecommerce.identity.StaffPrincipal;
import com.unispark.ecommerce.order.Orders;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.Sites;
import com.unispark.ecommerce.tenant.StoreLookup;
import com.unispark.ecommerce.tenant.StoreStatus;
import com.unispark.ecommerce.tenant.StoreSummary;

/**
 * The logged-in staff member and their store, for the admin and driver layouts, and the billing notice the admin
 * layout shows above every page. All null on public pages.
 */
@ControllerAdvice(basePackageClasses = AdminModelAdvice.class)
class AdminModelAdvice {

    /** A trial ending within this many days is announced. */
    static final int TRIAL_NOTICE_DAYS = 7;
    private static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Damascus");

    private final StoreLookup stores;
    private final Subscriptions subscriptions;
    private final Orders orders;
    private final Sites sites;
    private final ColorSchemes schemes;
    private final Clock clock;

    AdminModelAdvice(StoreLookup stores, Subscriptions subscriptions, Orders orders, Sites sites, ColorSchemes schemes,
            Clock clock) {
        this.stores = stores;
        this.subscriptions = subscriptions;
        this.orders = orders;
        this.sites = sites;
        this.schemes = schemes;
        this.clock = clock;
    }

    /**
     * Which part of the admin the request is in, for the navigation: the first path segment after {@code /admin}
     * ({@code orders} for {@code /admin/orders/1008}), {@code dashboard} for {@code /admin} itself.
     */
    @ModelAttribute("adminSection")
    String adminSection(HttpServletRequest request) {
        var path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith("/admin")) {
            return null;
        }
        var rest = path.substring("/admin".length());
        var segments = rest.split("/");
        return segments.length < 2 || segments[1].isEmpty() ? "dashboard" : segments[1];
    }

    /** The store's address, shown on the sign at the top of the navigation. */
    @ModelAttribute("storeHost")
    String storeHost() {
        var staff = currentStaff();
        return staff == null ? null : sites.storeHost(staff.storeSlug());
    }

    /**
     * Orders waiting for the merchant's confirmation, for the badge on "Orders": on full pages only, so HTMX
     * fragments and form posts don't pay for the count.
     */
    @ModelAttribute("ordersToConfirm")
    Long ordersToConfirm(HttpServletRequest request) {
        if (currentStaff() == null || adminSection(request) == null || !"GET".equals(request.getMethod())
                || request.getHeader("HX-Request") != null) {
            return null;
        }
        return orders.counts(clock.instant()).toConfirm();
    }

    /**
     * The member's light or dark choice, for the layout's data-scheme: on full pages only. Unlike the orders badge,
     * a form sent back with mistakes (a POST that renders its page) needs it too, or the page turns light.
     */
    @ModelAttribute("colorScheme")
    String colorScheme(HttpServletRequest request) {
        var staff = currentStaff();
        if (staff == null || request.getHeader("HX-Request") != null) {
            return null;
        }
        return schemes.ofStaff(staff.staffId()).attribute();
    }

    @ModelAttribute("currentStaff")
    StaffPrincipal currentStaff() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof StaffPrincipal staff ? staff : null;
    }

    @ModelAttribute("currentStore")
    StoreSummary currentStore() {
        return TenantContext.currentTenantId().flatMap(stores::findById).orElse(null);
    }

    /**
     * Everyone sees that the store is suspended; owners and managers also see an invoice to pay and a trial about
     * to end, with a link to the billing page.
     */
    @ModelAttribute("billingNotice")
    BillingNotice billingNotice() {
        var staff = currentStaff();
        var store = currentStore();
        if (staff == null || store == null) {
            return null;
        }
        if (store.status() == StoreStatus.SUSPENDED) {
            return new BillingNotice("suspended", null);
        }
        if (!staff.role().managesStaff()) {
            return null;
        }
        var subscription = subscriptions.forStore(store.id()).orElse(null);
        if (subscription == null) {
            return null;
        }
        if (subscription.status() == SubscriptionStatus.PAST_DUE) {
            return new BillingNotice("due", subscription.graceUntil());
        }
        var today = LocalDate.now(clock.withZone(BILLING_ZONE));
        if (subscription.status() == SubscriptionStatus.TRIAL
                && !subscription.periodEnd().isAfter(today.plusDays(TRIAL_NOTICE_DAYS))) {
            return new BillingNotice("trial", subscription.periodEnd());
        }
        return null;
    }

    /**
     * @param kind suspended, due or trial (the message key suffix)
     * @param date the grace or trial end, or null
     */
    record BillingNotice(String kind, LocalDate date) {
    }
}
