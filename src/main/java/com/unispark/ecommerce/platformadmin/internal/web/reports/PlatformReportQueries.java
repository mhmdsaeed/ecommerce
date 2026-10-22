package com.unispark.ecommerce.platformadmin.internal.web.reports;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.unispark.ecommerce.media.ImageStore;
import com.unispark.ecommerce.report.Chart.Entry;
import com.unispark.ecommerce.report.ReportPeriod;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.Money;
import com.unispark.ecommerce.shared.PlatformDatabase;

/**
 * The platform reports (revenue, stores, activity, operations), as SQL on the platform database, which sees every
 * store (it bypasses row-level security, so it stays in the platform admin module). Activity is totals per store and
 * across stores only: no customer names, phones or addresses leave a store here.
 */
@Component
class PlatformReportQueries {

    /** A trial ending within this many days is listed. */
    static final int TRIALS_AHEAD = 14;
    /** A trading store without an order for this many days is listed as quiet. */
    static final int QUIET_DAYS = 30;
    static final int ROWS = 20;
    private static final String NOT_SOLD = "('CANCELLED', 'REFUSED', 'RETURNED')";

    private final PlatformDatabase platform;
    private final ImageStore images;

    PlatformReportQueries(PlatformDatabase platform, ImageStore images) {
        this.platform = platform;
        this.images = images;
    }

    // Revenue

    /**
     * @param plans    per plan: paying subscriptions (active or past due), trials, and their monthly value in dollars
     * @param invoices invoices issued in the period, by status and currency
     * @param overdue  open invoices past their due date, now
     * @param payments money received in the period, per group and currency
     */
    record Revenue(List<PlanLine> plans, Money monthly, List<InvoiceLine> invoices, List<InvoiceLine> overdue,
            List<Entry> payments) {
    }

    record PlanLine(String code, LocalizedText name, long paying, long trials, Money monthly) {
    }

    record InvoiceLine(String status, long count, Money amount) {
    }

    Revenue revenue(ReportPeriod period, LocalDate today) {
        var plans = jdbc().sql("""
                        SELECT p.code, p.name ->> 'ar' AS name_ar, p.name ->> 'en' AS name_en, p.monthly_price_usd,
                               count(s.id) FILTER (WHERE s.status IN ('ACTIVE', 'PAST_DUE')) AS paying,
                               count(s.id) FILTER (WHERE s.status = 'TRIAL') AS trials
                        FROM plan p LEFT JOIN subscription s ON s.plan_code = p.code
                        GROUP BY p.code, p.name, p.monthly_price_usd
                        ORDER BY p.monthly_price_usd, p.code
                        """)
                .query((rows, i) -> {
                    var paying = rows.getLong("paying");
                    return new PlanLine(rows.getString("code"), text(rows, "name"), paying, rows.getLong("trials"),
                            usd(rows.getBigDecimal("monthly_price_usd").multiply(BigDecimal.valueOf(paying))));
                })
                .list();
        var monthly = usd(plans.stream().map(line -> line.monthly().amount()).reduce(BigDecimal.ZERO, BigDecimal::add));
        var invoices = jdbc().sql("""
                        SELECT status, currency, count(*) AS invoices, sum(amount) AS amount
                        FROM invoice WHERE issued_on BETWEEN :from AND :to
                        GROUP BY status, currency ORDER BY status, currency
                        """)
                .param("from", period.from()).param("to", period.to())
                .query((rows, i) -> new InvoiceLine(rows.getString("status"), rows.getLong("invoices"),
                        money(rows, "amount", "currency")))
                .list();
        var overdue = jdbc().sql("""
                        SELECT 'OVERDUE' AS status, currency, count(*) AS invoices, sum(amount) AS amount
                        FROM invoice WHERE status = 'OPEN' AND due_date < :today
                        GROUP BY currency ORDER BY currency
                        """)
                .param("today", today)
                .query((rows, i) -> new InvoiceLine(rows.getString("status"), rows.getLong("invoices"),
                        money(rows, "amount", "currency")))
                .list();
        var found = new HashMap<String, Entry>();
        var currencies = new ArrayList<Currency>();
        jdbc().sql("""
                        SELECT (date_trunc(:unit, p.recorded_at AT TIME ZONE 'Asia/Damascus'))::date AS grp,
                               i.currency, count(*) AS payments, sum(p.amount) AS amount
                        FROM invoice_payment p JOIN invoice i ON i.id = p.invoice_id
                        WHERE p.recorded_at >= :start AND p.recorded_at < :end
                        GROUP BY 1, 2 ORDER BY 2
                        """)
                .param("unit", period.unit().sql())
                .param("start", Timestamp.from(period.start())).param("end", Timestamp.from(period.end()))
                .query(rows -> {
                    var currency = Currency.valueOf(rows.getString("currency"));
                    if (!currencies.contains(currency)) {
                        currencies.add(currency);
                    }
                    found.put(rows.getObject("grp", LocalDate.class) + "/" + currency, new Entry(
                            rows.getObject("grp", LocalDate.class), currency, rows.getLong("payments"),
                            rows.getBigDecimal("amount")));
                });
        return new Revenue(plans, monthly, invoices, overdue, filled(period, currencies, found));
    }

    // Stores

    /**
     * @param byStatus  every store, now, by status
     * @param signups   stores created in the period, per group
     * @param cohort    the period's new stores by where their subscription is now: trying, paying, stopped
     * @param stopped   subscriptions that became suspended or cancelled in the period (each counted once)
     * @param reopened  subscriptions that left suspended or cancelled in the period
     * @param recordedSince the day of the first recorded status change (Damascus): earlier days count nothing;
     *                      null before any
     */
    record Stores(Map<String, Long> byStatus, long created, List<Entry> signups, Map<String, Long> cohort,
            long stopped, long reopened, LocalDate recordedSince, List<TrialEnding> trialsEnding) {
    }

    record TrialEnding(UUID storeId, String slug, LocalizedText name, LocalDate ends, String plan) {
    }

    Stores stores(ReportPeriod period, LocalDate today) {
        var byStatus = new LinkedHashMap<String, Long>();
        jdbc().sql("SELECT status, count(*) AS stores FROM store GROUP BY status ORDER BY 2 DESC, 1")
                .query(rows -> {
                    byStatus.put(rows.getString("status"), rows.getLong("stores"));
                });
        var found = new HashMap<String, Entry>();
        jdbc().sql("""
                        SELECT (date_trunc(:unit, created_at AT TIME ZONE 'Asia/Damascus'))::date AS grp,
                               count(*) AS stores
                        FROM store WHERE created_at >= :start AND created_at < :end
                        GROUP BY 1
                        """)
                .param("unit", period.unit().sql())
                .param("start", Timestamp.from(period.start())).param("end", Timestamp.from(period.end()))
                .query(rows -> {
                    var group = rows.getObject("grp", LocalDate.class);
                    found.put(group + "/null", new Entry(group, null, rows.getLong("stores"), null));
                });
        var signups = filled(period, Collections.singletonList(null), found);
        var created = signups.stream().mapToLong(Entry::count).sum();
        var cohort = new LinkedHashMap<String, Long>();
        jdbc().sql("""
                        SELECT CASE WHEN s.status = 'TRIAL' THEN 'TRYING'
                                    WHEN s.status IN ('ACTIVE', 'PAST_DUE') THEN 'PAYING'
                                    WHEN s.status IS NULL THEN 'NONE'
                                    ELSE 'STOPPED' END AS stage, count(*) AS stores
                        FROM store st LEFT JOIN subscription s ON s.store_id = st.id
                        WHERE st.created_at >= :start AND st.created_at < :end
                        GROUP BY 1
                        """)
                .param("start", Timestamp.from(period.start())).param("end", Timestamp.from(period.end()))
                .query(rows -> {
                    cohort.put(rows.getString("stage"), rows.getLong("stores"));
                });
        // From the recorded status changes (V19); rows the migration wrote for older subscriptions are a snapshot of
        // their status then, not a change, so they never count.
        var changes = jdbc().sql("""
                        SELECT count(DISTINCT subscription_id) FILTER (
                                   WHERE to_status IN ('SUSPENDED', 'CANCELLED')) AS stopped,
                               count(DISTINCT subscription_id) FILTER (
                                   WHERE from_status IN ('SUSPENDED', 'CANCELLED')
                                     AND to_status NOT IN ('SUSPENDED', 'CANCELLED')) AS reopened
                        FROM subscription_status_change
                        WHERE NOT backfilled AND changed_at >= :start AND changed_at < :end
                        """)
                .param("start", Timestamp.from(period.start())).param("end", Timestamp.from(period.end()))
                .query((rows, i) -> new long[] {rows.getLong("stopped"), rows.getLong("reopened")})
                .single();
        var recordedSince = jdbc().sql("SELECT min(changed_at) FROM subscription_status_change WHERE NOT backfilled")
                .query((rows, i) -> rows.getTimestamp("min") == null ? null
                        : rows.getTimestamp("min").toInstant().atZone(ReportPeriod.ZONE).toLocalDate())
                .list().getFirst();
        var trials = jdbc().sql("""
                        SELECT st.id, st.slug, st.name ->> 'ar' AS name_ar, st.name ->> 'en' AS name_en,
                               s.current_period_end, s.plan_code
                        FROM subscription s JOIN store st ON st.id = s.store_id
                        WHERE s.status = 'TRIAL' AND s.current_period_end <= :until
                        ORDER BY s.current_period_end, st.slug
                        LIMIT :rows
                        """)
                .param("until", today.plusDays(TRIALS_AHEAD)).param("rows", ROWS)
                .query((rows, i) -> new TrialEnding(rows.getObject("id", UUID.class), rows.getString("slug"),
                        text(rows, "name"), rows.getObject("current_period_end", LocalDate.class),
                        rows.getString("plan_code")))
                .list();
        return new Stores(Collections.unmodifiableMap(byStatus), created, signups,
                Collections.unmodifiableMap(cohort), changes[0], changes[1], recordedSince, trials);
    }

    // Activity

    /**
     * @param totals per currency, the period's sales across every store (orders except cancelled, refused and
     *               returned, in the currency shoppers paid)
     * @param quiet  trading stores without an order for {@link #QUIET_DAYS} days, the longest quiet first
     */
    record Activity(List<CurrencyTotal> totals, long storesSelling, List<Entry> points, List<StoreLine> busiest,
            List<QuietStore> quiet) {
    }

    record CurrencyTotal(long orders, Money sales) {
    }

    record StoreLine(UUID storeId, String slug, LocalizedText name, long orders, List<Money> sales) {
    }

    /** @param lastOrderAt null when the store never had an order */
    record QuietStore(UUID storeId, String slug, LocalizedText name, Instant lastOrderAt, Instant createdAt) {
    }

    Activity activity(ReportPeriod period, Instant now) {
        var totals = jdbc().sql("""
                        SELECT display_currency AS currency, count(*) AS orders, sum(total_display) AS sales
                        FROM shop_order
                        WHERE placed_at >= :start AND placed_at < :end AND status NOT IN %s
                        GROUP BY 1 ORDER BY 1
                        """.formatted(NOT_SOLD))
                .param("start", Timestamp.from(period.start())).param("end", Timestamp.from(period.end()))
                .query((rows, i) -> new CurrencyTotal(rows.getLong("orders"), money(rows, "sales", "currency")))
                .list();
        var selling = jdbc().sql("SELECT count(DISTINCT tenant_id) FROM shop_order WHERE placed_at >= :start"
                        + " AND placed_at < :end AND status NOT IN " + NOT_SOLD)
                .param("start", Timestamp.from(period.start())).param("end", Timestamp.from(period.end()))
                .query(Long.class).single();
        var found = new HashMap<String, Entry>();
        var currencies = totals.stream().map(total -> total.sales().currency()).toList();
        jdbc().sql("""
                        SELECT (date_trunc(:unit, placed_at AT TIME ZONE 'Asia/Damascus'))::date AS grp,
                               display_currency AS currency, count(*) AS orders, sum(total_display) AS sales
                        FROM shop_order
                        WHERE placed_at >= :start AND placed_at < :end AND status NOT IN %s
                        GROUP BY 1, 2
                        """.formatted(NOT_SOLD))
                .param("unit", period.unit().sql())
                .param("start", Timestamp.from(period.start())).param("end", Timestamp.from(period.end()))
                .query(rows -> {
                    var group = rows.getObject("grp", LocalDate.class);
                    var currency = Currency.valueOf(rows.getString("currency"));
                    found.put(group + "/" + currency, new Entry(group, currency, rows.getLong("orders"),
                            rows.getBigDecimal("sales")));
                });
        var sales = new HashMap<UUID, List<Money>>();
        var busiest = new LinkedHashMap<UUID, StoreLine>();
        jdbc().sql("""
                        SELECT st.id, st.slug, st.name ->> 'ar' AS name_ar, st.name ->> 'en' AS name_en,
                               o.display_currency AS currency, count(*) AS orders,
                               sum(o.total_display) AS sales
                        FROM shop_order o JOIN store st ON st.id = o.tenant_id
                        WHERE o.placed_at >= :start AND o.placed_at < :end AND o.status NOT IN %s
                        GROUP BY st.id, st.slug, st.name, o.display_currency
                        """.formatted(NOT_SOLD))
                .param("start", Timestamp.from(period.start())).param("end", Timestamp.from(period.end()))
                .query(rows -> {
                    var id = rows.getObject("id", UUID.class);
                    sales.computeIfAbsent(id, key -> new ArrayList<>()).add(money(rows, "sales", "currency"));
                    var orders = rows.getLong("orders") + (busiest.containsKey(id) ? busiest.get(id).orders() : 0);
                    busiest.put(id, new StoreLine(id, rows.getString("slug"), text(rows, "name"), orders, List.of()));
                });
        var top = busiest.values().stream()
                .sorted(Comparator.comparingLong(StoreLine::orders).reversed().thenComparing(StoreLine::slug))
                .limit(ROWS)
                .map(line -> new StoreLine(line.storeId(), line.slug(), line.name(), line.orders(),
                        List.copyOf(sales.get(line.storeId()))))
                .toList();
        var quiet = jdbc().sql("""
                        SELECT st.id, st.slug, st.name ->> 'ar' AS name_ar, st.name ->> 'en' AS name_en, st.created_at,
                               last_order.at AS last_order_at
                        FROM store st
                        LEFT JOIN LATERAL (SELECT max(o.placed_at) AS at FROM shop_order o
                                           WHERE o.tenant_id = st.id) last_order ON TRUE
                        WHERE st.status IN ('TRIAL', 'ACTIVE') AND st.created_at < :since
                          AND (last_order.at IS NULL OR last_order.at < :since)
                        ORDER BY last_order.at NULLS FIRST, st.created_at
                        LIMIT :rows
                        """)
                .param("since", Timestamp.from(now.minus(Duration.ofDays(QUIET_DAYS))))
                .param("rows", ROWS)
                .query((rows, i) -> new QuietStore(rows.getObject("id", UUID.class), rows.getString("slug"),
                        text(rows, "name"), instant(rows, "last_order_at"), instant(rows, "created_at")))
                .list();
        return new Activity(totals, selling, filled(period, currencies, found), top, quiet);
    }

    // Operations

    /**
     * @param messages  per channel: sent, failed and still pending in the period
     * @param failures  the latest failed messages, without their recipient
     * @param jobs      background jobs by state, now (JobRunr keeps finished ones for a while)
     * @param stuck     events published more than an hour ago whose listener has not completed
     * @param storage   per store, largest first; null when storage could not be read
     */
    record Operations(List<ChannelLine> messages, List<FailedMessage> failures, Map<String, Long> jobs,
            List<FailedJob> failedJobs, long stuck, List<StorageLine> storage, long storageBytes) {
    }

    record ChannelLine(String channel, long sent, long failed, long pending) {
    }

    record FailedMessage(String slug, String template, String channel, String error, Instant at) {
    }

    record FailedJob(String signature, Instant at) {
    }

    record StorageLine(UUID storeId, String slug, LocalizedText name, long objects, long bytes) {
    }

    Operations operations(ReportPeriod period, Instant now) {
        var messages = jdbc().sql("""
                        SELECT channel, count(*) FILTER (WHERE status = 'SENT') AS sent,
                               count(*) FILTER (WHERE status = 'FAILED') AS failed,
                               count(*) FILTER (WHERE status = 'PENDING') AS pending
                        FROM notification_log
                        WHERE created_at >= :start AND created_at < :end
                        GROUP BY channel ORDER BY channel
                        """)
                .param("start", Timestamp.from(period.start())).param("end", Timestamp.from(period.end()))
                .query((rows, i) -> new ChannelLine(rows.getString("channel"), rows.getLong("sent"),
                        rows.getLong("failed"), rows.getLong("pending")))
                .list();
        var failures = jdbc().sql("""
                        SELECT st.slug, n.template, n.channel, left(n.error, 160) AS error, n.updated_at
                        FROM notification_log n JOIN store st ON st.id = n.tenant_id
                        WHERE n.status = 'FAILED' AND n.created_at >= :start AND n.created_at < :end
                        ORDER BY n.updated_at DESC
                        LIMIT :rows
                        """)
                .param("start", Timestamp.from(period.start())).param("end", Timestamp.from(period.end()))
                .param("rows", ROWS)
                .query((rows, i) -> new FailedMessage(rows.getString("slug"), rows.getString("template"),
                        rows.getString("channel"), rows.getString("error"), instant(rows, "updated_at")))
                .list();
        var jobs = new LinkedHashMap<String, Long>();
        jdbc().sql("SELECT state, count(*) AS jobs FROM jobrunr_jobs GROUP BY state ORDER BY 2 DESC, 1")
                .query(rows -> {
                    jobs.put(rows.getString("state"), rows.getLong("jobs"));
                });
        // JobRunr keeps its times in UTC, without a zone.
        var failedJobs = jdbc().sql("""
                        SELECT jobsignature, updatedat FROM jobrunr_jobs WHERE state = 'FAILED'
                        ORDER BY updatedat DESC LIMIT :rows
                        """)
                .param("rows", ROWS)
                .query((rows, i) -> new FailedJob(rows.getString("jobsignature"),
                        rows.getTimestamp("updatedat").toLocalDateTime().toInstant(ZoneOffset.UTC)))
                .list();
        var stuck = jdbc().sql("""
                        SELECT count(*) FROM event_publication
                        WHERE completion_date IS NULL AND publication_date < :before
                        """)
                .param("before", Timestamp.from(now.minus(Duration.ofHours(1))))
                .query(Long.class).single();
        var usage = images.usageByStore();
        List<StorageLine> storage = null;
        var total = 0L;
        if (usage.isPresent()) {
            var names = new HashMap<UUID, StorageLine>();
            jdbc().sql("SELECT id, slug, name ->> 'ar' AS name_ar, name ->> 'en' AS name_en FROM store")
                    .query(rows -> {
                        var id = rows.getObject("id", UUID.class);
                        names.put(id, new StorageLine(id, rows.getString("slug"), text(rows, "name"), 0, 0));
                    });
            storage = usage.get().entrySet().stream()
                    .filter(entry -> names.containsKey(entry.getKey()))
                    .map(entry -> {
                        var store = names.get(entry.getKey());
                        return new StorageLine(store.storeId(), store.slug(), store.name(), entry.getValue().objects(),
                                entry.getValue().bytes());
                    })
                    .sorted(Comparator.comparingLong(StorageLine::bytes).reversed())
                    .toList();
            total = usage.get().values().stream().mapToLong(ImageStore.Usage::bytes).sum();
        }
        return new Operations(messages, failures, Collections.unmodifiableMap(jobs), failedJobs, stuck,
                storage == null ? null : storage.stream().limit(ROWS).toList(), total);
    }

    /** Every group of the period for each currency (or null for counts), so gaps show as empty columns. */
    private static List<Entry> filled(ReportPeriod period, List<Currency> currencies, Map<String, Entry> found) {
        var entries = new ArrayList<Entry>();
        for (var currency : currencies) {
            for (var group : period.groups()) {
                entries.add(found.getOrDefault(group + "/" + currency,
                        new Entry(group, currency, 0, currency == null ? null : BigDecimal.ZERO)));
            }
        }
        return List.copyOf(entries);
    }

    private JdbcClient jdbc() {
        return platform.jdbc();
    }

    private static Money usd(BigDecimal amount) {
        return Money.of(amount, Currency.USD);
    }

    private static Money money(ResultSet rows, String amount, String currency) throws SQLException {
        var value = rows.getBigDecimal(amount);
        return Money.of(value == null ? BigDecimal.ZERO : value, Currency.valueOf(rows.getString(currency)));
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        var value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** A name selected as {column}_ar and {column}_en (the JSON's fields). */
    private static LocalizedText text(ResultSet rows, String column) throws SQLException {
        var ar = rows.getString(column + "_ar");
        var en = rows.getString(column + "_en");
        return ar == null && en == null ? LocalizedText.of("?", null) : LocalizedText.of(ar, en);
    }
}
