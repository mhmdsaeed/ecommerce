package com.unispark.ecommerce.billing.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.function.IntSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.unispark.ecommerce.billing.BillingCycle;
import com.unispark.ecommerce.billing.InvoiceStatus;
import com.unispark.ecommerce.billing.SubscriptionStatus;
import com.unispark.ecommerce.billing.internal.persistence.Invoice;
import com.unispark.ecommerce.billing.internal.persistence.InvoiceRepository;
import com.unispark.ecommerce.billing.internal.persistence.PlanRepository;
import com.unispark.ecommerce.billing.internal.persistence.Subscription;
import com.unispark.ecommerce.billing.internal.persistence.SubscriptionRepository;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.Money;
import com.unispark.ecommerce.tenant.StoreAdministration;

/**
 * The daily run (see {@link BillingCycle}). Each store is handled in its own transaction under its subscription's
 * row lock, so one store's failure does not stop the others and a payment recorded meanwhile is never lost.
 */
@Service
class BillingCycleService implements BillingCycle {

    private static final Logger log = LoggerFactory.getLogger(BillingCycleService.class);
    private static final List<SubscriptionStatus> BILLED =
            List.of(SubscriptionStatus.TRIAL, SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE);

    private final SubscriptionRepository subscriptions;
    private final InvoiceRepository invoices;
    private final PlanRepository plans;
    private final StoreAdministration stores;
    private final BillingProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    BillingCycleService(SubscriptionRepository subscriptions, InvoiceRepository invoices, PlanRepository plans,
            StoreAdministration stores, BillingProperties properties, PlatformTransactionManager transactions,
            Clock clock) {
        this.subscriptions = subscriptions;
        this.invoices = invoices;
        this.plans = plans;
        this.stores = stores;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    @Override
    public CycleReport run(LocalDate today) {
        var trials = startMissingTrials(today);
        var issued = 0;
        for (var store : subscriptions.findStoresToBill(BILLED, today)) {
            issued += each(store, "bill", () -> bill(store, today));
        }
        var suspended = 0;
        for (var store : subscriptions.findStoresPastGrace(today)) {
            suspended += each(store, "suspend", () -> suspendIfUnpaid(store, today));
        }
        var report = new CycleReport(trials, issued, suspended);
        log.info("Billing run for {}: {}", today, report);
        return report;
    }

    /** Today in Syrian time, for the scheduled run. */
    LocalDate today() {
        return LocalDate.now(clock.withZone(BillingProperties.ZONE));
    }

    /** Trials for stores created while the listener was down, or before billing existed. */
    int startMissingTrials(LocalDate today) {
        var started = transaction.execute(status -> subscriptions.startMissingTrials(properties.defaultPlan(), today,
                today.plusDays(properties.trialDays())));
        return started == null ? 0 : started;
    }

    /** A new store's trial, unless it already has a subscription. */
    void startTrial(UUID storeId, LocalDate today) {
        transaction.executeWithoutResult(status -> {
            if (subscriptions.findByStoreId(storeId).isEmpty()) {
                subscriptions.save(Subscription.trial(storeId, properties.defaultPlan(), today,
                        today.plusDays(properties.trialDays())));
            }
        });
    }

    /**
     * The platform admin activated a store. If billing had suspended its subscription, billing resumes: with a
     * fresh grace period while an invoice is still open, otherwise in good standing. Without this, a store
     * activated without paying would never be billed or suspended again.
     */
    void resumeBilling(UUID storeId, LocalDate today) {
        transaction.executeWithoutResult(status -> subscriptions.findByStoreIdForUpdate(storeId)
                .filter(subscription -> subscription.getStatus() == SubscriptionStatus.SUSPENDED)
                .ifPresent(subscription -> {
                    if (invoices.existsByStoreIdAndStatus(storeId, InvoiceStatus.OPEN)) {
                        subscription.reinstated(today, today.plusDays(properties.graceDays()));
                    } else {
                        subscription.settled(today);
                    }
                    log.info("Billing resumed for store {} after the platform admin activated it", storeId);
                }));
    }

    private int each(UUID store, String step, IntSupplier work) {
        try {
            return work.getAsInt();
        } catch (RuntimeException e) {
            log.error("Billing could not {} store {}", step, store, e);
            return 0;
        }
    }

    /** Invoices every month that has started by today, one by one, each due on its first day. */
    private int bill(UUID storeId, LocalDate today) {
        var issued = transaction.execute(status -> {
            var subscription = subscriptions.findByStoreIdForUpdate(storeId).orElseThrow();
            var count = 0;
            while (subscription.getStatus().billed() && !subscription.getPeriodEnd().isAfter(today)) {
                var plan = plans.findByCode(subscription.getPlanCode()).orElseThrow();
                var start = subscription.getPeriodEnd();
                var end = start.plusMonths(1);
                var price = Money.of(plan.getMonthlyPriceUsd(), Currency.USD);
                if (!price.isPositive()) {
                    // A free plan: the month starts with nothing to pay, but an earlier unpaid invoice still counts.
                    subscription.startedFreePeriod();
                    continue;
                }
                invoices.save(Invoice.forPeriod(storeId, invoices.nextNumber(),
                        "Subscription " + plan.getCode() + " " + start.format(DateTimeFormatter.ISO_LOCAL_DATE)
                                + " to " + end.format(DateTimeFormatter.ISO_LOCAL_DATE),
                        plan.getCode(), price, today, start, end));
                subscription.billedNextPeriod(start.plusDays(properties.graceDays()));
                count++;
            }
            return count;
        });
        return issued == null ? 0 : issued;
    }

    /** Grace is over: if an invoice is still open, billing suspends the subscription and the store. */
    private int suspendIfUnpaid(UUID storeId, LocalDate today) {
        var suspended = transaction.execute(status -> {
            var subscription = subscriptions.findByStoreIdForUpdate(storeId).orElseThrow();
            if (subscription.getStatus() != SubscriptionStatus.PAST_DUE
                    || !subscription.getGraceUntil().isBefore(today)) {
                return false;
            }
            var oldest = invoices.findFirstByStoreIdAndStatusOrderByDueDateAscNumberAsc(storeId, InvoiceStatus.OPEN);
            if (oldest.isEmpty()) {
                subscription.settled(today);
                return false;
            }
            subscription.suspended();
            return stores.suspendForNonPayment(storeId, "Unpaid invoice INV-" + oldest.get().getNumber()
                    + " due " + oldest.get().getDueDate());
        });
        return Boolean.TRUE.equals(suspended) ? 1 : 0;
    }
}
