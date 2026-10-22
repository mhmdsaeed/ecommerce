package com.unispark.ecommerce.platformadmin.internal.web.reports;

import static com.unispark.ecommerce.TestAccounts.PASSWORD;
import static com.unispark.ecommerce.TestAccounts.PLATFORM;
import static com.unispark.ecommerce.TestAccounts.fromNewAddress;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

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

import com.unispark.ecommerce.TestAccounts;
import com.unispark.ecommerce.TestImages;
import com.unispark.ecommerce.TestStores;
import com.unispark.ecommerce.TestcontainersConfig;
import com.unispark.ecommerce.billing.BillingCycle;
import com.unispark.ecommerce.billing.InvoicePaymentMethod;
import com.unispark.ecommerce.billing.Invoices;
import com.unispark.ecommerce.billing.Invoices.InvoiceDraft;
import com.unispark.ecommerce.billing.Invoices.PaymentDraft;
import com.unispark.ecommerce.identity.internal.persistence.PlatformUserRepository;
import com.unispark.ecommerce.media.ImageStore;
import com.unispark.ecommerce.report.ReportPeriod;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.TenantContext;

/**
 * The platform reports over a shared database: other tests' stores are there too, so each check looks for this
 * test's own stores, or compares a total before and after.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class PlatformReportsIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformUserRepository platformUsers;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    BillingCycle cycle;

    @Autowired
    Invoices invoices;

    @Autowired
    ImageStore images;

    @Autowired
    PlatformReportQueries queries;

    @Autowired
    Clock clock;

    LocalDate today;
    ReportPeriod period;
    String slug;
    UUID store;

    @BeforeEach
    void createStore() {
        today = LocalDate.now(clock.withZone(ReportPeriod.ZONE));
        period = ReportPeriod.of("today", null, null, today);
        slug = TestStores.randomSlug();
        store = TestStores.create(jdbc, slug, "TRIAL");
        // The billing run starts its 14-day trial.
        cycle.run(today);
    }

    @Test
    void reportsRevenueFromInvoicesAndPayments() {
        var before = queries.revenue(period, today);
        var invoice = ((Invoices.Created) invoices.create(new InvoiceDraft(store, "Setup", "15", Currency.USD,
                today.minusDays(1)))).invoiceId();
        invoices.recordPayment(invoice, new PaymentDraft(InvoicePaymentMethod.BANK, "5", null), "ops@platform.test");

        var after = queries.revenue(period, today);

        assertThat(openCount(after.invoices()) - openCount(before.invoices())).isEqualTo(1);
        assertThat(openCount(after.overdue()) - openCount(before.overdue())).isEqualTo(1);
        assertThat(after.payments()).anySatisfy(entry -> {
            assertThat(entry.group()).isEqualTo(today);
            assertThat(entry.currency()).isEqualTo(Currency.USD);
        });
        assertThat(after.plans()).extracting(PlatformReportQueries.PlanLine::code).contains("basic");
    }

    @Test
    void reportsStoresAndTrialsEndingSoon() {
        var stores = queries.stores(period, today);

        assertThat(stores.byStatus().get("TRIAL")).isPositive();
        assertThat(stores.created()).isPositive();
        assertThat(stores.cohort().get("TRYING")).isPositive();
        assertThat(stores.trialsEnding()).anySatisfy(trial -> {
            assertThat(trial.slug()).isEqualTo(slug);
            assertThat(trial.ends()).isEqualTo(today.plusDays(14));
        });
    }

    @Test
    void countsStoppedSubscriptionsFromTheirStatusHistory() {
        var before = queries.stores(period, today);

        // Suspended, suspended again (no change), back to active, suspended once more.
        for (var status : List.of("SUSPENDED", "SUSPENDED", "ACTIVE", "SUSPENDED")) {
            jdbc.update("UPDATE subscription SET status = ? WHERE store_id = ?", status, store);
        }

        var history = jdbc.query("""
                SELECT coalesce(c.from_status, '-') || '>' || c.to_status FROM subscription_status_change c
                JOIN subscription s ON s.id = c.subscription_id
                WHERE s.store_id = ? AND NOT c.backfilled ORDER BY c.changed_at, c.id
                """, (rows, i) -> rows.getString(1), store);
        assertThat(history).containsExactlyInAnyOrder("->TRIAL", "TRIAL>SUSPENDED", "SUSPENDED>ACTIVE",
                "ACTIVE>SUSPENDED");
        var after = queries.stores(period, today);
        // Counted once however often it stopped.
        assertThat(after.stopped() - before.stopped()).isEqualTo(1);
        assertThat(after.reopened() - before.reopened()).isEqualTo(1);
        assertThat(after.recordedSince()).isNotNull().isBeforeOrEqualTo(today);
        // Nothing before the period counts: the day before has none of these changes.
        var yesterday = ReportPeriod.of("custom", today.minusDays(1).toString(), today.minusDays(1).toString(), today);
        assertThat(queries.stores(yesterday, today).stopped()).isLessThanOrEqualTo(after.stopped() - 1);

        assertThatThrownBy(() -> jdbc.update("UPDATE subscription_status_change SET to_status = 'ACTIVE'"
                + " WHERE store_id = ?", store)).hasMessageContaining("append-only");
    }

    @Test
    void listsQuietStoresAndFailedMessagesWithoutRecipients() {
        // A store open for 40 days without an order.
        jdbc.update("UPDATE store SET created_at = now() - interval '40 days' WHERE id = ?", store);
        TenantContext.runAs(store, () -> jdbc.update("""
                INSERT INTO notification_log (id, tenant_id, dedupe_key, template, channel, recipient, status, attempts,
                                              error, created_at, updated_at, version)
                VALUES (?, ?, ?, 'order_placed', 'SMS', '+963933000999', 'FAILED', 3, 'Provider timeout', now(), now(), 0)
                """, UUID.randomUUID(), store, UUID.randomUUID().toString()));

        var activity = queries.activity(period, clock.instant());
        assertThat(activity.quiet()).anySatisfy(quiet -> {
            assertThat(quiet.slug()).isEqualTo(slug);
            assertThat(quiet.lastOrderAt()).isNull();
        });

        var operations = queries.operations(period, clock.instant());
        assertThat(operations.messages()).anySatisfy(line -> {
            assertThat(line.channel()).isEqualTo("SMS");
            assertThat(line.failed()).isPositive();
        });
        assertThat(operations.failures()).anySatisfy(failure -> {
            assertThat(failure.slug()).isEqualTo(slug);
            assertThat(failure.error()).isEqualTo("Provider timeout");
        });
        assertThat(operations.failures().toString()).doesNotContain("+963933000999");
    }

    @Test
    void countsStoragePerStore() throws Exception {
        images.storePublic(store, new ByteArrayInputStream(TestImages.jpeg(800, 600)));

        var operations = queries.operations(period, clock.instant());

        assertThat(operations.storage()).isNotNull().anySatisfy(line -> {
            assertThat(line.slug()).isEqualTo(slug);
            assertThat(line.objects()).isPositive();
            assertThat(line.bytes()).isPositive();
        });
        assertThat(operations.storageBytes()).isPositive();
    }

    @Test
    void platformAdminsSeeThePagesAndCsv() throws Exception {
        mvc.perform(get(PLATFORM + "/platform/reports/revenue")).andExpect(redirectedUrlPattern("**/platform/login"));
        // Signed out: the header has the title and the languages, no menu.
        mvc.perform(get(PLATFORM + "/platform/login"))
                .andExpect(content().string(not(containsString("<details class=\"lg:hidden\">"))));
        var admin = platformAdmin();
        mvc.perform(get(PLATFORM + "/platform/reports").session(admin))
                .andExpect(redirectedUrl("/platform/reports/revenue"));
        var revenuePage = mvc.perform(get(PLATFORM + "/platform/reports/revenue").session(admin))
                .andReturn().getResponse().getContentAsString();
        // The sections twice, in the header for wide screens and in the Menu panel for phones; both mark this one.
        assertThat(revenuePage).contains("<details class=\"lg:hidden\">");
        assertThat(Pattern.compile("<a href=\"/platform/reports\"[^>]*aria-current=\"page\"")
                .matcher(revenuePage).results().count()).isEqualTo(2);
        mvc.perform(get(PLATFORM + "/platform/reports/revenue").session(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("الإيراد الشهري المتكرر"),
                        containsString("href=\"/platform/reports/stores?period=30d\""))));
        mvc.perform(get(PLATFORM + "/platform/reports/stores").session(admin).param("period", "today"))
                .andExpect(content().string(allOf(containsString(slug), containsString("المتاجر الجديدة يومياً"))));
        mvc.perform(get(PLATFORM + "/platform/reports/activity").session(admin))
                .andExpect(content().string(containsString("المبيعات في كل المتاجر")));
        mvc.perform(get(PLATFORM + "/platform/reports/operations").session(admin))
                .andExpect(content().string(allOf(containsString("المهام في الخلفية"), containsString("تخزين الصور"))));

        var csv = mvc.perform(get(PLATFORM + "/platform/reports/stores.csv").session(admin)
                        .param("table", "trials").param("period", "today"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8)).startsWith("المتجر,العنوان,الباقة,تنتهي")
                .contains(slug);
        mvc.perform(get(PLATFORM + "/platform/reports/stores.csv").session(admin).param("table", "nope"))
                .andExpect(status().isNotFound());
    }

    private static long openCount(List<PlatformReportQueries.InvoiceLine> lines) {
        return lines.stream().filter(line -> line.amount().currency() == Currency.USD)
                .filter(line -> "OPEN".equals(line.status()) || "OVERDUE".equals(line.status()))
                .mapToLong(PlatformReportQueries.InvoiceLine::count).sum();
    }

    private MockHttpSession platformAdmin() throws Exception {
        var email = TestAccounts.platformAdmin(platformUsers, passwords);
        var result = mvc.perform(post(PLATFORM + "/platform/login").with(csrf()).with(fromNewAddress())
                        .param("username", email).param("password", PASSWORD))
                .andExpect(redirectedUrl("/platform"))
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
