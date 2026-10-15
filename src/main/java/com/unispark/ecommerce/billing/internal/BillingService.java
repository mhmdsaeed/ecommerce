package com.unispark.ecommerce.billing.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.billing.InvoiceStatus;
import com.unispark.ecommerce.billing.Invoices;
import com.unispark.ecommerce.billing.Plans;
import com.unispark.ecommerce.billing.SubscriptionStatus;
import com.unispark.ecommerce.billing.Subscriptions;
import com.unispark.ecommerce.billing.internal.persistence.Invoice;
import com.unispark.ecommerce.billing.internal.persistence.InvoicePayment;
import com.unispark.ecommerce.billing.internal.persistence.InvoicePaymentRepository;
import com.unispark.ecommerce.billing.internal.persistence.InvoiceRepository;
import com.unispark.ecommerce.billing.internal.persistence.Plan;
import com.unispark.ecommerce.billing.internal.persistence.PlanRepository;
import com.unispark.ecommerce.billing.internal.persistence.Subscription;
import com.unispark.ecommerce.billing.internal.persistence.SubscriptionRepository;
import com.unispark.ecommerce.shared.Amounts;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.FieldProblem;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.Money;
import com.unispark.ecommerce.tenant.StoreAdministration;

/**
 * Plans, subscriptions, invoices and payments. Locks are taken in one order (invoice, subscription, store), so a
 * payment and the daily job never deadlock.
 */
@Service
@Transactional
class BillingService implements Plans, Subscriptions, Invoices {

    private final PlanRepository plans;
    private final SubscriptionRepository subscriptions;
    private final InvoiceRepository invoices;
    private final InvoicePaymentRepository payments;
    private final StoreAdministration stores;
    private final BillingProperties properties;
    private final Clock clock;

    BillingService(PlanRepository plans, SubscriptionRepository subscriptions, InvoiceRepository invoices,
            InvoicePaymentRepository payments, StoreAdministration stores, BillingProperties properties,
            Clock clock) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.invoices = invoices;
        this.payments = payments;
        this.stores = stores;
        this.properties = properties;
        this.clock = clock;
    }

    // Plans

    @Override
    @Transactional(readOnly = true)
    public List<PlanView> all() {
        return plans.findAll().stream()
                .sorted(Comparator.comparing((Plan plan) -> !plan.isActive())
                        .thenComparing(Plan::getMonthlyPriceUsd).thenComparing(Plan::getCode))
                .map(BillingService::view)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlanView> find(String code) {
        return code == null ? Optional.empty() : plans.findByCode(code).map(BillingService::view);
    }

    @Override
    public SaveResult save(PlanDraft draft) {
        var problems = new ArrayList<FieldProblem>();
        var code = draft.code() == null ? "" : draft.code().strip().toLowerCase(Locale.ROOT);
        if (!code.matches("[a-z0-9][a-z0-9-]{0,30}")) {
            problems.add(new FieldProblem("code", "invalid"));
        }
        var nameAr = clean(draft.nameAr(), MAX_NAME_LENGTH);
        var nameEn = clean(draft.nameEn(), MAX_NAME_LENGTH);
        if (nameAr == null && nameEn == null) {
            problems.add(new FieldProblem("name", "required"));
        }
        var price = Amounts.parse(draft.monthlyPrice());
        if (price.isEmpty()) {
            problems.add(new FieldProblem("monthlyPrice", "invalid"));
        }
        var products = limit(draft.products(), "products", problems);
        var staff = limit(draft.staff(), "staff", problems);
        var orders = limit(draft.ordersPerMonth(), "ordersPerMonth", problems);
        if (!problems.isEmpty()) {
            return new Plans.Invalid(problems);
        }
        var plan = plans.findByCode(code).orElseGet(() -> new Plan(code));
        plan.update(LocalizedText.of(nameAr, nameEn), price.get(), new Limits(products, staff, orders), draft.active());
        plans.save(plan);
        return new Saved(code);
    }

    // Subscriptions

    @Override
    @Transactional(readOnly = true)
    public Optional<SubscriptionView> forStore(UUID storeId) {
        return subscriptions.findByStoreId(storeId).map(BillingService::view);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionView> list(SubscriptionStatus status) {
        var found = status == null ? subscriptions.findAllByOrderByPeriodEndAsc()
                : subscriptions.findByStatusOrderByPeriodEndAsc(status);
        return found.stream().map(BillingService::view).toList();
    }

    @Override
    public boolean changePlan(UUID storeId, String planCode) {
        var plan = plans.findByCode(planCode == null ? "" : planCode).filter(Plan::isActive);
        var subscription = subscriptions.findByStoreIdForUpdate(storeId);
        if (plan.isEmpty() || subscription.isEmpty()) {
            return false;
        }
        subscription.get().changePlan(plan.get().getCode());
        return true;
    }

    // Invoices

    @Override
    @Transactional(readOnly = true)
    public InvoicePage list(InvoiceStatus status, int page) {
        var request = PageRequest.of(Math.max(page, 0), PAGE_SIZE + 1);
        var found = status == null ? invoices.findAllByOrderByNumberDesc(request)
                : invoices.findByStatusOrderByNumberDesc(status, request);
        var rows = views(found.stream().limit(PAGE_SIZE).toList());
        return new InvoicePage(rows, Math.max(page, 0), found.size() > PAGE_SIZE);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceView> ofStore(UUID storeId) {
        return views(invoices.findByStoreIdOrderByIssuedOnDescNumberDesc(storeId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InvoiceView> find(UUID invoiceId) {
        return invoices.findById(invoiceId).map(invoice -> views(List.of(invoice)).getFirst());
    }

    @Override
    public CreateResult create(InvoiceDraft draft) {
        var problems = new ArrayList<FieldProblem>();
        if (draft.storeId() == null || stores.find(draft.storeId()).isEmpty()) {
            problems.add(new FieldProblem("store", "unknown"));
        }
        var description = clean(draft.description(), MAX_TEXT_LENGTH);
        if (description == null) {
            problems.add(new FieldProblem("description", "required"));
        }
        var amount = Amounts.parse(draft.amount()).filter(value -> value.signum() > 0);
        if (amount.isEmpty()) {
            problems.add(new FieldProblem("amount", "invalid"));
        }
        if (draft.currency() == null) {
            problems.add(new FieldProblem("currency", "required"));
        }
        if (draft.dueDate() == null) {
            problems.add(new FieldProblem("dueDate", "required"));
        }
        if (!problems.isEmpty()) {
            return new Invoices.Invalid(problems);
        }
        var invoice = Invoice.manual(draft.storeId(), invoices.nextNumber(), description,
                Money.of(amount.get(), draft.currency()), today(), draft.dueDate());
        return new Created(invoices.save(invoice).getId());
    }

    @Override
    public PaymentResult recordPayment(UUID invoiceId, PaymentDraft draft, String recordedBy) {
        var found = invoices.findByIdForUpdate(invoiceId).filter(invoice -> invoice.getStatus() == InvoiceStatus.OPEN);
        if (found.isEmpty()) {
            return new NotOpen();
        }
        var invoice = found.get();
        var due = invoice.getAmount().minus(paid(invoice));
        var problems = new ArrayList<FieldProblem>();
        if (draft.method() == null) {
            problems.add(new FieldProblem("method", "required"));
        }
        var amount = Amounts.parse(draft.amount()).filter(value -> value.signum() > 0);
        if (amount.isEmpty()) {
            problems.add(new FieldProblem("amount", "invalid"));
        } else if (amount.get().compareTo(due.amount()) > 0) {
            problems.add(new FieldProblem("amount", "too-much"));
        }
        var reference = draft.reference() == null || draft.reference().isBlank() ? null : draft.reference().strip();
        if (reference != null && reference.length() > MAX_TEXT_LENGTH) {
            problems.add(new FieldProblem("reference", "too-long"));
        }
        if (!problems.isEmpty()) {
            return new Invoices.Invalid(problems);
        }
        payments.save(new InvoicePayment(invoice.getId(), draft.method(), reference, amount.get(), recordedBy,
                clock.instant()));
        var settled = amount.get().compareTo(due.amount()) == 0;
        if (settled) {
            invoice.paid(clock.instant());
            invoices.flush();
            settleIfNothingOwed(invoice.getStoreId());
        }
        return new PaymentRecorded(settled);
    }

    @Override
    public VoidResult voidInvoice(UUID invoiceId, String reason) {
        var text = clean(reason, MAX_TEXT_LENGTH);
        if (text == null) {
            return new ReasonRequired();
        }
        var found = invoices.findByIdForUpdate(invoiceId).filter(invoice -> invoice.getStatus() == InvoiceStatus.OPEN);
        if (found.isEmpty()) {
            return new NotOpen();
        }
        found.get().voided(clock.instant(), text);
        invoices.flush();
        settleIfNothingOwed(found.get().getStoreId());
        return new Voided();
    }

    @Override
    public Optional<String> payTo() {
        return Optional.ofNullable(properties.payTo()).filter(text -> !text.isBlank()).map(String::strip);
    }

    /**
     * Once a store owes nothing, its subscription is in good standing again, and a store billing suspended
     * reopens. A suspension by the platform admin stays.
     */
    private void settleIfNothingOwed(UUID storeId) {
        // Lock first, then look: the daily job issues invoices under this lock, so an invoice it was issuing
        // meanwhile is committed and seen here, rather than settled over.
        var subscription = subscriptions.findByStoreIdForUpdate(storeId);
        if (invoices.existsByStoreIdAndStatus(storeId, InvoiceStatus.OPEN)) {
            return;
        }
        subscription
                .filter(found -> found.getStatus() == SubscriptionStatus.PAST_DUE
                        || found.getStatus() == SubscriptionStatus.SUSPENDED)
                .ifPresent(found -> found.settled(today()));
        stores.resumeAfterPayment(storeId);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(BillingProperties.ZONE));
    }

    private Money paid(Invoice invoice) {
        return payments.findByInvoiceIdInOrderByRecordedAtAsc(List.of(invoice.getId())).stream()
                .map(payment -> Money.of(payment.getAmount(), invoice.getAmount().currency()))
                .reduce(Money.zero(invoice.getAmount().currency()), Money::plus);
    }

    private List<InvoiceView> views(List<Invoice> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        var byInvoice = payments.findByInvoiceIdInOrderByRecordedAtAsc(found.stream().map(Invoice::getId).toList())
                .stream().collect(Collectors.groupingBy(InvoicePayment::getInvoiceId));
        return found.stream().map(invoice -> {
            var currency = invoice.getAmount().currency();
            var paymentViews = byInvoice.getOrDefault(invoice.getId(), List.of()).stream()
                    .map(payment -> new PaymentView(payment.getMethod(), payment.getReference(),
                            Money.of(payment.getAmount(), currency), payment.getRecordedBy(),
                            payment.getRecordedAt()))
                    .toList();
            var paid = paymentViews.stream().map(PaymentView::amount).reduce(Money.zero(currency), Money::plus);
            return new InvoiceView(invoice.getId(), invoice.getStoreId(), invoice.getNumber(),
                    invoice.getDescription(), invoice.getPlanCode(), invoice.getAmount(), paid, invoice.getStatus(),
                    invoice.getIssuedOn(), invoice.getDueDate(), invoice.getPeriodStart(), invoice.getPeriodEnd(),
                    invoice.getPaidAt(), invoice.getVoidedAt(), invoice.getVoidReason(), paymentViews);
        }).toList();
    }

    private static PlanView view(Plan plan) {
        return new PlanView(plan.getCode(), plan.getName(), Money.of(plan.getMonthlyPriceUsd(), Currency.USD),
                plan.getLimits(), plan.isActive());
    }

    private static SubscriptionView view(Subscription subscription) {
        return new SubscriptionView(subscription.getStoreId(), subscription.getPlanCode(), subscription.getStatus(),
                subscription.getPeriodStart(), subscription.getPeriodEnd(), subscription.getGraceUntil());
    }

    /** A blank limit is no limit; otherwise a whole number of at least 1. */
    private static Integer limit(String typed, String field, List<FieldProblem> problems) {
        if (typed == null || typed.isBlank()) {
            return null;
        }
        var value = Amounts.parse(typed, 0).filter(number -> number.signum() > 0
                && number.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0);
        if (value.isEmpty()) {
            problems.add(new FieldProblem(field, "invalid"));
            return null;
        }
        return value.get().intValueExact();
    }

    private static String clean(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return null;
        }
        var stripped = text.strip();
        return stripped.length() <= maxLength ? stripped : stripped.substring(0, maxLength);
    }
}
