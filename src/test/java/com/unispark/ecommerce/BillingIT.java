package com.unispark.ecommerce;

import static com.unispark.ecommerce.TestAccounts.MAIN;
import static com.unispark.ecommerce.TestAccounts.PASSWORD;
import static com.unispark.ecommerce.TestAccounts.PLATFORM;
import static com.unispark.ecommerce.TestAccounts.fromNewAddress;
import static com.unispark.ecommerce.TestAccounts.storefront;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.unispark.ecommerce.billing.BillingCycle;
import com.unispark.ecommerce.billing.InvoicePaymentMethod;
import com.unispark.ecommerce.billing.InvoiceStatus;
import com.unispark.ecommerce.billing.Invoices;
import com.unispark.ecommerce.billing.Invoices.InvoiceView;
import com.unispark.ecommerce.billing.Invoices.PaymentDraft;
import com.unispark.ecommerce.billing.Invoices.PaymentRecorded;
import com.unispark.ecommerce.billing.Plans;
import com.unispark.ecommerce.billing.Plans.PlanDraft;
import com.unispark.ecommerce.billing.SubscriptionStatus;
import com.unispark.ecommerce.billing.Subscriptions;
import com.unispark.ecommerce.billing.Subscriptions.SubscriptionView;
import com.unispark.ecommerce.identity.StaffRole;
import com.unispark.ecommerce.identity.internal.persistence.PlatformUserRepository;
import com.unispark.ecommerce.identity.internal.persistence.StaffUserRepository;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.tenant.StoreAdministration;
import com.unispark.ecommerce.tenant.StoreAdministration.Changed;
import com.unispark.ecommerce.tenant.StoreAdministration.ScreeningRequired;
import com.unispark.ecommerce.tenant.StoreRegistration;
import com.unispark.ecommerce.tenant.StoreRegistration.NewStore;
import com.unispark.ecommerce.tenant.StoreStatus;
import com.unispark.ecommerce.tenant.SuspensionKind;

/**
 * M12 done-when: a store that does not pay is suspended, and its storefront shows "temporarily unavailable"
 * (SPEC 8, 9.4, 14). The daily run is called with explicit days, as the scheduler would on each of them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class BillingIT {

    private static final ZoneId SYRIA = ZoneId.of("Asia/Damascus");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffUserRepository staffUsers;

    @Autowired
    PlatformUserRepository platformUsers;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    BillingCycle cycle;

    @Autowired
    Subscriptions subscriptions;

    @Autowired
    Invoices invoices;

    @Autowired
    Plans plans;

    @Autowired
    StoreAdministration stores;

    @Autowired
    StoreRegistration registration;

    @Autowired
    TransactionTemplate transaction;

    LocalDate today;
    String slug;
    UUID store;

    @BeforeEach
    void createStore() {
        today = LocalDate.now(SYRIA);
        slug = TestStores.randomSlug();
        store = TestStores.create(jdbc, slug, "TRIAL");
        TestAccounts.staff(staffUsers, passwords, store, "هالة", "owner@" + slug + ".test", null, StaffRole.OWNER);
        TestAccounts.staff(staffUsers, passwords, store, "رامي", "staff@" + slug + ".test", null, StaffRole.STAFF);
        cycle.run(today);
    }

    @Test
    void unpaidStoreIsSuspendedAndReopensOncePaid() throws Exception {
        var trial = subscription();
        assertThat(trial.status()).isEqualTo(SubscriptionStatus.TRIAL);
        assertThat(trial.planCode()).isEqualTo("basic");
        assertThat(trial.periodEnd()).isEqualTo(today.plusDays(14));

        // The trial ends: the first month is invoiced, due at once, with a week's grace.
        var trialEnd = today.plusDays(14);
        cycle.run(trialEnd);
        var invoice = onlyInvoice();
        assertThat(invoice.amount().amount()).isEqualByComparingTo("10.00");
        assertThat(invoice.amount().currency().name()).isEqualTo("USD");
        assertThat(invoice.dueDate()).isEqualTo(trialEnd);
        assertThat(invoice.periodStart()).isEqualTo(trialEnd);
        assertThat(invoice.periodEnd()).isEqualTo(trialEnd.plusMonths(1));
        assertThat(subscription().status()).isEqualTo(SubscriptionStatus.PAST_DUE);
        assertThat(subscription().graceUntil()).isEqualTo(trialEnd.plusDays(7));
        var owner = TestAccounts.logIn(mvc, slug, "owner@" + slug + ".test");
        mvc.perform(get(MAIN + "/admin").session(owner))
                .andExpect(content().string(containsString("لديك فاتورة بانتظار الدفع")));
        mvc.perform(get(storefront(slug) + "/")).andExpect(status().isOk());

        // Still within grace on its last day; suspended the day after.
        cycle.run(trialEnd.plusDays(7));
        assertThat(storeStatus()).isEqualTo(StoreStatus.TRIAL);
        cycle.run(trialEnd.plusDays(8));
        assertThat(storeStatus()).isEqualTo(StoreStatus.SUSPENDED);
        assertThat(stores.find(store).orElseThrow().suspensionKind()).isEqualTo(SuspensionKind.BILLING);
        assertThat(subscription().status()).isEqualTo(SubscriptionStatus.SUSPENDED);
        // ErrorPagesIT renders the page itself through a real server.
        mvc.perform(get(storefront(slug) + "/")).andExpect(status().isServiceUnavailable());
        // The owner still reaches the admin, sees why, and how to pay.
        mvc.perform(get(MAIN + "/admin/billing").session(owner))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("متجرك موقوف"), containsString(invoice.label()),
                        containsString("موقوف لعدم الدفع"), containsString("تواصل معنا لدفع فاتورتك"))));
        // Running again changes nothing.
        cycle.run(trialEnd.plusDays(9));
        assertThat(invoices.ofStore(store)).hasSize(1);

        // The platform admin records the money: part of it, then the rest.
        var admin = platformAdmin();
        mvc.perform(post(PLATFORM + "/platform/invoices/" + invoice.id() + "/payments").session(admin).with(csrf())
                        .param("method", "WALLET").param("amount", "٤").param("reference", "SC-123"))
                .andExpect(redirectedUrl("/platform/invoices/" + invoice.id() + "?done=payment"));
        assertThat(storeStatus()).isEqualTo(StoreStatus.SUSPENDED);
        mvc.perform(post(PLATFORM + "/platform/invoices/" + invoice.id() + "/payments").session(admin).with(csrf())
                        .param("method", "WALLET").param("amount", "7"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("هذا أكثر من المتبقي على الفاتورة")));
        mvc.perform(post(PLATFORM + "/platform/invoices/" + invoice.id() + "/payments").session(admin).with(csrf())
                        .param("method", "CASH").param("amount", "6"))
                .andExpect(redirectedUrl("/platform/invoices/" + invoice.id() + "?done=paid"));

        var paid = onlyInvoice();
        assertThat(paid.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(paid.payments()).extracting(payment -> payment.recordedBy()).allMatch(by -> by.endsWith("@platform.test"));
        // Never screened, so it goes back to TRIAL rather than ACTIVE; a fresh month starts today.
        assertThat(storeStatus()).isEqualTo(StoreStatus.TRIAL);
        assertThat(subscription().status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscription().periodStart()).isEqualTo(today);
        assertThat(subscription().graceUntil()).isNull();
        mvc.perform(get(storefront(slug) + "/")).andExpect(status().isOk());
        mvc.perform(get(PLATFORM + "/platform/invoices/" + invoice.id()).session(admin))
                .andExpect(content().string(allOf(containsString("مدفوعة"), containsString("SC-123"))));
    }

    @Test
    void aSuspensionByThePlatformAdminIsNotLiftedByPaying() throws Exception {
        cycle.run(today.plusDays(14));
        var admin = platformAdmin();
        mvc.perform(post(PLATFORM + "/platform/stores/" + store + "/suspend").session(admin).with(csrf()))
                .andExpect(redirectedUrl("/platform/stores/" + store + "?error=reason"));
        mvc.perform(post(PLATFORM + "/platform/stores/" + store + "/suspend").session(admin).with(csrf())
                        .param("reason", "Fraud reports"))
                .andExpect(redirectedUrl("/platform/stores/" + store + "?done=suspended"));

        var invoice = onlyInvoice();
        assertThat(invoices.recordPayment(invoice.id(), new PaymentDraft(InvoicePaymentMethod.BANK, "10", null),
                "ops@platform.test")).isEqualTo(new PaymentRecorded(true));

        assertThat(storeStatus()).isEqualTo(StoreStatus.SUSPENDED);
        assertThat(stores.find(store).orElseThrow().suspensionReason()).isEqualTo("Fraud reports");
        // Billing never suspends it a second time either.
        cycle.run(today.plusDays(30));
        assertThat(stores.find(store).orElseThrow().suspensionKind()).isEqualTo(SuspensionKind.MANUAL);
    }

    @Test
    void voidingTheLastOpenInvoiceReopensTheStore() {
        cycle.run(today.plusDays(14));
        cycle.run(today.plusDays(22));
        assertThat(storeStatus()).isEqualTo(StoreStatus.SUSPENDED);

        var invoice = onlyInvoice();
        assertThat(invoices.voidInvoice(invoice.id(), " ")).isInstanceOf(Invoices.ReasonRequired.class);
        assertThat(invoices.voidInvoice(invoice.id(), "Free month for the launch")).isInstanceOf(Invoices.Voided.class);

        assertThat(onlyInvoice().status()).isEqualTo(InvoiceStatus.VOID);
        assertThat(storeStatus()).isEqualTo(StoreStatus.TRIAL);
        assertThat(invoices.recordPayment(invoice.id(), new PaymentDraft(InvoicePaymentMethod.CASH, "10", null),
                "ops@platform.test")).isInstanceOf(Invoices.NotOpen.class);
    }

    @Test
    void missedMonthsAreBilledOneByOneAndOnlyOnce() {
        // The job did not run for two months after the trial.
        var later = today.plusDays(14).plusMonths(1);
        cycle.run(later);
        assertThat(storeStatus()).isEqualTo(StoreStatus.SUSPENDED);
        cycle.run(later);
        assertThat(invoices.ofStore(store)).extracting(InvoiceView::periodStart)
                .containsExactly(today.plusDays(14).plusMonths(1), today.plusDays(14));
        // Suspended subscriptions are not billed further.
        cycle.run(later.plusMonths(3));
        assertThat(invoices.ofStore(store)).hasSize(2);
    }

    @Test
    void theNewPlanAppliesFromTheNextInvoice() {
        assertThat(subscriptions.changePlan(store, "nonexistent")).isFalse();
        assertThat(subscriptions.changePlan(store, "pro")).isTrue();
        cycle.run(today.plusDays(14));
        assertThat(onlyInvoice().amount().amount()).isEqualByComparingTo("25.00");
        assertThat(onlyInvoice().planCode()).isEqualTo("pro");

        // A free plan starts its months without an invoice.
        var free = "free-" + slug.substring(1, 7);
        assertThat(plans.save(new PlanDraft(free, "مجانية", "Free", "0", "", "", "", true)))
                .isInstanceOf(Plans.Saved.class);
        invoices.voidInvoice(onlyInvoice().id(), "Moved to the free plan");
        subscriptions.changePlan(store, free);
        cycle.run(today.plusDays(14).plusMonths(1));
        assertThat(invoices.ofStore(store)).hasSize(1);
        assertThat(subscription().status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscription().periodStart()).isEqualTo(today.plusDays(14).plusMonths(1));
    }

    @Test
    void movingToAFreePlanDoesNotForgiveAnUnpaidInvoice() {
        cycle.run(today.plusDays(14));
        var free = "free-" + slug.substring(1, 7);
        plans.save(new PlanDraft(free, "مجانية", "Free", "0", "", "", "", true));
        subscriptions.changePlan(store, free);

        // The free month starts, but the first month is still owed and its grace is long over.
        cycle.run(today.plusDays(14).plusMonths(1));

        assertThat(invoices.ofStore(store)).hasSize(1);
        assertThat(onlyInvoice().status()).isEqualTo(InvoiceStatus.OPEN);
        assertThat(subscription().periodStart()).isEqualTo(today.plusDays(14).plusMonths(1));
        assertThat(subscription().status()).isEqualTo(SubscriptionStatus.SUSPENDED);
        assertThat(storeStatus()).isEqualTo(StoreStatus.SUSPENDED);
    }

    @Test
    void activatingABillingSuspendedStoreWithoutPaymentResumesBilling() {
        cycle.run(today.plusDays(14));
        cycle.run(today.plusDays(22));
        assertThat(subscription().status()).isEqualTo(SubscriptionStatus.SUSPENDED);

        assertThat(stores.activate(store, true, null, "ops@platform.test")).isEqualTo(new Changed(StoreStatus.ACTIVE));

        // The store gets a fresh grace period for what it still owes, from the day it was activated.
        var activatedOn = LocalDate.now(SYRIA);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(subscription().status()).isEqualTo(SubscriptionStatus.PAST_DUE);
            assertThat(subscription().graceUntil()).isEqualTo(activatedOn.plusDays(7));
        });
        assertThat(subscription().periodStart()).isEqualTo(activatedOn);
        cycle.run(activatedOn.plusDays(7));
        assertThat(storeStatus()).isEqualTo(StoreStatus.ACTIVE);
        // Still unpaid once that grace is over: billing suspends it again.
        cycle.run(activatedOn.plusDays(8));
        assertThat(storeStatus()).isEqualTo(StoreStatus.SUSPENDED);
        assertThat(stores.find(store).orElseThrow().suspensionKind()).isEqualTo(SuspensionKind.BILLING);
    }

    @Test
    void activationRequiresTheScreeningOnce() throws Exception {
        assertThat(stores.activate(store, false, null, "ops@platform.test")).isInstanceOf(ScreeningRequired.class);
        var admin = platformAdmin();
        mvc.perform(post(PLATFORM + "/platform/stores/" + store + "/activate").session(admin).with(csrf()))
                .andExpect(redirectedUrl("/platform/stores/" + store + "?error=screening"));
        mvc.perform(post(PLATFORM + "/platform/stores/" + store + "/activate").session(admin).with(csrf())
                        .param("screened", "true").param("notes", "Checked OFAC and EU lists"))
                .andExpect(redirectedUrl("/platform/stores/" + store + "?done=activated"));

        var record = stores.find(store).orElseThrow();
        assertThat(record.status()).isEqualTo(StoreStatus.ACTIVE);
        assertThat(record.screenedAt()).isNotNull();
        assertThat(record.screenedBy()).endsWith("@platform.test");
        assertThat(record.screeningNotes()).isEqualTo("Checked OFAC and EU lists");

        // Suspended and activated again: no second screening, and a billing suspension now reopens as ACTIVE.
        assertThat(stores.suspend(store, "Checking a complaint")).isInstanceOf(Changed.class);
        assertThat(stores.activate(store, false, null, "ops@platform.test")).isEqualTo(new Changed(StoreStatus.ACTIVE));
        cycle.run(today.plusDays(14));
        cycle.run(today.plusDays(22));
        invoices.recordPayment(onlyInvoice().id(), new PaymentDraft(InvoicePaymentMethod.WALLET, "10", null), "ops");
        assertThat(storeStatus()).isEqualTo(StoreStatus.ACTIVE);
    }

    @Test
    void newStoresStartATrialWhenTheyRegister() {
        var id = UUID.randomUUID();
        transaction.executeWithoutResult(status -> registration.register(new NewStore(id, TestStores.randomSlug(),
                LocalizedText.of("متجر", "Store"), Locale.forLanguageTag("ar"), null)));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(subscriptions.forStore(id))
                .hasValueSatisfying(trial -> assertThat(trial.status()).isEqualTo(SubscriptionStatus.TRIAL)));
    }

    @Test
    void platformAdminScreensListAndEditBilling() throws Exception {
        cycle.run(today.plusDays(14));
        var admin = platformAdmin();
        mvc.perform(get(PLATFORM + "/platform/stores").session(admin))
                .andExpect(content().string(allOf(containsString(slug), containsString("بانتظار الدفع"))));
        mvc.perform(get(PLATFORM + "/platform/stores/" + store).session(admin))
                .andExpect(content().string(allOf(containsString(onlyInvoice().label()),
                        containsString("تحققتُ من المالك والمتجر"))));
        mvc.perform(get(PLATFORM + "/platform/subscriptions?status=PAST_DUE").session(admin))
                .andExpect(content().string(containsString("متجر " + slug)));
        mvc.perform(get(PLATFORM + "/platform/invoices?status=OPEN").session(admin))
                .andExpect(content().string(containsString(onlyInvoice().label())));
        mvc.perform(get(PLATFORM + "/platform/plans").session(admin))
                .andExpect(content().string(allOf(containsString("الأساسية"), containsString("الاحترافية"))));

        // A hand-written invoice, with the form's problems reported first.
        mvc.perform(post(PLATFORM + "/platform/invoices").session(admin).with(csrf())
                        .param("store", store.toString()).param("amount", "0").param("currency", "USD"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("اكتب ما تخص الفاتورة"),
                        containsString("أدخل مبلغاً أكبر من صفر"), containsString("اختر تاريخ الاستحقاق"))));
        mvc.perform(post(PLATFORM + "/platform/invoices").session(admin).with(csrf())
                        .param("store", store.toString()).param("description", "Setup and product upload")
                        .param("amount", "150,000").param("currency", "SYP").param("dueDate", today.toString()))
                .andExpect(redirectedUrlPattern("/platform/invoices/*?done=created"));
        assertThat(invoices.ofStore(store)).filteredOn(invoice -> invoice.periodStart() == null).singleElement()
                .satisfies(manual -> {
                    assertThat(manual.amount().amount()).isEqualByComparingTo(new BigDecimal("150000"));
                    assertThat(manual.amount().currency().name()).isEqualTo("SYP");
                    assertThat(manual.description()).isEqualTo("Setup and product upload");
                });

        // Plans: a bad price and limit are reported; a valid plan is saved.
        mvc.perform(post(PLATFORM + "/platform/plans").session(admin).with(csrf())
                        .param("code", "Bad Code").param("monthlyPrice", "x").param("staff", "-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("استخدم من 1 إلى 31"),
                        containsString("أدخل الاسم بلغة واحدة"), containsString("أدخل سعراً"))));
        var code = "p" + slug.substring(1, 8);
        mvc.perform(post(PLATFORM + "/platform/plans").session(admin).with(csrf())
                        .param("code", code).param("nameAr", "متوسطة").param("nameEn", "Medium")
                        .param("monthlyPrice", "15").param("products", "500").param("_active", "on"))
                .andExpect(redirectedUrl("/platform/plans"));
        assertThat(plans.find(code)).hasValueSatisfying(plan -> {
            assertThat(plan.monthlyPrice().amount()).isEqualByComparingTo("15");
            assertThat(plan.limits().products()).isEqualTo(500);
            assertThat(plan.limits().staff()).isNull();
            assertThat(plan.active()).isFalse();
        });
        // An inactive plan cannot be chosen.
        mvc.perform(post(PLATFORM + "/platform/stores/" + store + "/plan").session(admin).with(csrf()).param("plan", code))
                .andExpect(redirectedUrl("/platform/stores/" + store + "?error=plan"));
    }

    @Test
    void merchantsSeeOnlyTheirOwnBilling() throws Exception {
        cycle.run(today.plusDays(14));
        var other = TestStores.create(jdbc, TestStores.randomSlug(), "TRIAL");
        cycle.run(today);
        cycle.run(today.plusDays(14));
        var otherLabel = invoices.ofStore(other).getFirst().label();

        var owner = TestAccounts.logIn(mvc, slug, "owner@" + slug + ".test");
        mvc.perform(get(MAIN + "/admin/billing").session(owner))
                .andExpect(content().string(allOf(containsString(onlyInvoice().label()), not(containsString(otherLabel)),
                        containsString("الأساسية"), containsString("100 منتج"))));
        var staff = TestAccounts.logIn(mvc, slug, "staff@" + slug + ".test");
        mvc.perform(get(MAIN + "/admin/billing").session(staff)).andExpect(status().isForbidden());
        mvc.perform(get(MAIN + "/admin").session(staff))
                .andExpect(content().string(not(containsString("لديك فاتورة بانتظار الدفع"))));
        mvc.perform(get(PLATFORM + "/platform/invoices").session(owner)).andExpect(status().isForbidden());
    }

    private MockHttpSession platformAdmin() throws Exception {
        var email = TestAccounts.platformAdmin(platformUsers, passwords);
        var result = mvc.perform(post(PLATFORM + "/platform/login").with(csrf()).with(fromNewAddress())
                        .param("username", email).param("password", PASSWORD))
                .andExpect(redirectedUrl("/platform"))
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private SubscriptionView subscription() {
        return subscriptions.forStore(store).orElseThrow();
    }

    private InvoiceView onlyInvoice() {
        assertThat(invoices.ofStore(store)).hasSize(1);
        return invoices.ofStore(store).getFirst();
    }

    private StoreStatus storeStatus() {
        return stores.find(store).orElseThrow().status();
    }
}
