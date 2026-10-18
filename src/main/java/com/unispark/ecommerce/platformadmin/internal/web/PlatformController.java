package com.unispark.ecommerce.platformadmin.internal.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.unispark.ecommerce.billing.InvoiceStatus;
import com.unispark.ecommerce.billing.Invoices;
import com.unispark.ecommerce.billing.SubscriptionStatus;
import com.unispark.ecommerce.billing.Subscriptions;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.PlatformDatabase;

/**
 * Platform admin login and dashboard. The dashboard is the operator's ledger: what needs following up (invoices
 * awaiting payment, stores past due, trials about to end, suspended stores, new stores), then the newest stores.
 */
@Controller
class PlatformController {

    static final int RECENT_STORES = 10;
    /** Trials ending within this many days, and stores created within it, are counted. */
    static final int WEEK = 7;
    /** Dates are shown in Syrian time (SPEC 18). */
    static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Damascus");

    private final PlatformDatabase platform;
    private final Subscriptions subscriptions;
    private final Invoices invoices;
    private final Clock clock;

    PlatformController(PlatformDatabase platform, Subscriptions subscriptions, Invoices invoices, Clock clock) {
        this.platform = platform;
        this.subscriptions = subscriptions;
        this.invoices = invoices;
        this.clock = clock;
    }

    @GetMapping("/platform/login")
    String login() {
        return "platformadmin/login";
    }

    @GetMapping("/platform")
    String dashboard(Model model) {
        var today = LocalDate.now(clock.withZone(DISPLAY_ZONE));
        var open = invoices.list(InvoiceStatus.OPEN, 0);
        var trialsEnding = subscriptions.list(SubscriptionStatus.TRIAL).stream()
                .filter(trial -> !trial.periodEnd().isAfter(today.plusDays(WEEK)))
                .count();
        var newStores = platform.jdbc().sql("SELECT count(*) FROM store WHERE created_at >= :since")
                .param("since", today.minusDays(WEEK).atStartOfDay(DISPLAY_ZONE).toOffsetDateTime())
                .query(Long.class).single();
        model.addAttribute("ledger", new Ledger(open.rows().size(), open.hasNext(),
                subscriptions.list(SubscriptionStatus.PAST_DUE).size(), trialsEnding,
                subscriptions.list(SubscriptionStatus.SUSPENDED).size(), newStores));
        model.addAttribute("week", WEEK);

        var stores = platform.jdbc().sql("""
                        SELECT id, slug, name ->> 'ar' AS name_ar, name ->> 'en' AS name_en, status, created_at
                        FROM store ORDER BY created_at DESC LIMIT :limit
                        """)
                .param("limit", RECENT_STORES)
                .query((row, index) -> new StoreRow(
                        row.getObject("id", UUID.class),
                        row.getString("slug"),
                        LocalizedText.of(row.getString("name_ar"), row.getString("name_en")),
                        row.getString("status"),
                        row.getTimestamp("created_at").toInstant().atZone(DISPLAY_ZONE).toLocalDate()))
                .list();
        model.addAttribute("stores", stores);
        model.addAttribute("storeCount", platform.jdbc().sql("SELECT count(*) FROM store").query(Long.class).single());
        return "platformadmin/dashboard";
    }

    /**
     * The dashboard's rows.
     *
     * @param openInvoices     open invoices on the first page of the list
     * @param moreOpenInvoices more than a page of them: shown as "N+"
     */
    record Ledger(int openInvoices, boolean moreOpenInvoices, int pastDue, long trialsEnding, int suspended,
            long newStores) {
    }

    record StoreRow(UUID id, String slug, LocalizedText name, String status, LocalDate createdOn) {
    }
}
