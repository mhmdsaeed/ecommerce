package com.unispark.ecommerce.report.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unispark.ecommerce.order.CodSettlement;
import com.unispark.ecommerce.report.ReportPeriod;
import com.unispark.ecommerce.report.StoreReports;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.Money;

/**
 * The reports, as SQL over the store's tables. Every query runs in the request's transaction, inside the store's
 * tenant context, so row-level security limits it to the store; none names a tenant itself.
 *
 * <p>Queries name the shared conditions as {SOLD}, {IN_PERIOD}, {NOT_SOLD}, {DELIVERED} and {HOURS} (see
 * {@link #sql}).
 */
@Service
@Transactional(readOnly = true)
class StoreReportService implements StoreReports {

    private static final Map<String, String> FRAGMENTS = Map.of(
            // Orders placed in the period.
            "{IN_PERIOD}", "o.placed_at >= :start AND o.placed_at < :end",
            // Statuses that do not count as sales.
            "{NOT_SOLD}", "('CANCELLED', 'REFUSED', 'RETURNED')",
            // Orders placed in the period that count as sales.
            "{SOLD}", "o.placed_at >= :start AND o.placed_at < :end"
                    + " AND o.status NOT IN ('CANCELLED', 'REFUSED', 'RETURNED')",
            "{DELIVERED}", "('DELIVERED', 'COMPLETED')",
            // Placed to delivered, in hours.
            "{HOURS}", "EXTRACT(EPOCH FROM (o.delivered_at - o.placed_at)) / 3600.0");

    private static final String SALES_TOTALS = sql("""
            SELECT o.display_currency AS currency, count(*) AS orders, sum(o.total_display) AS revenue
            FROM shop_order o
            WHERE {SOLD}
            GROUP BY 1 ORDER BY 1
            """);

    private static final String SALES_POINTS = sql("""
            SELECT (date_trunc(:unit, o.placed_at AT TIME ZONE 'Asia/Damascus'))::date AS grp,
                   o.display_currency AS currency, count(*) AS orders, sum(o.total_display) AS revenue
            FROM shop_order o
            WHERE {SOLD}
            GROUP BY 1, 2
            """);

    private static final String BEST_SELLERS = sql("""
            SELECT pv.product_id, coalesce(p.name, oi.product -> 'name') AS name, o.currency,
                   sum(oi.quantity) AS quantity, sum(oi.line_total) AS revenue
            FROM order_item oi
            JOIN shop_order o ON o.tenant_id = oi.tenant_id AND o.id = oi.order_id
            LEFT JOIN product_variant pv ON pv.tenant_id = oi.tenant_id AND pv.id = oi.variant_id
            LEFT JOIN product p ON p.tenant_id = pv.tenant_id AND p.id = pv.product_id
            WHERE {SOLD}
            GROUP BY pv.product_id, coalesce(p.name, oi.product -> 'name'), o.currency
            ORDER BY quantity DESC, revenue DESC
            LIMIT :rows
            """);

    /** Products on sale that sold nothing in the period; select what is needed in front of it. */
    private static final String IDLE_FROM = sql("""
            FROM product p
            WHERE p.status = 'ACTIVE' AND NOT EXISTS (
                SELECT 1 FROM order_item oi
                JOIN shop_order o ON o.tenant_id = oi.tenant_id AND o.id = oi.order_id
                JOIN product_variant pv ON pv.tenant_id = oi.tenant_id AND pv.id = oi.variant_id
                WHERE pv.product_id = p.id AND {SOLD})
            """);

    private static final String LOW_STOCK_ROWS = sql("""
            SELECT p.id, p.name, pv.option_values, s.on_hand - s.reserved AS available
            FROM stock_level s
            JOIN product_variant pv ON pv.tenant_id = s.tenant_id AND pv.id = s.variant_id
            JOIN product p ON p.tenant_id = pv.tenant_id AND p.id = pv.product_id
            WHERE pv.active AND p.status = 'ACTIVE' AND s.on_hand - s.reserved <= :low
            ORDER BY available, p.created_at, pv.position
            LIMIT :rows
            """);

    private static final String BY_STATUS = sql("""
            SELECT o.status, count(*) AS orders
            FROM shop_order o
            WHERE {IN_PERIOD}
            GROUP BY 1 ORDER BY 2 DESC, 1
            """);

    private static final String LOST = sql("""
            SELECT o.number, o.status, o.placed_at, o.customer_name, last_change.payload ->> 'note' AS reason
            FROM shop_order o
            LEFT JOIN LATERAL (
                SELECT e.payload FROM order_event e
                WHERE e.tenant_id = o.tenant_id AND e.order_id = o.id AND e.to_status = o.status
                ORDER BY e.position DESC LIMIT 1) last_change ON TRUE
            WHERE {IN_PERIOD} AND o.status IN {NOT_SOLD}
            ORDER BY o.placed_at DESC
            LIMIT :rows
            """);

    private static final String DELIVERY_TIME = sql("""
            SELECT count(*) AS delivered, avg(hours) AS average,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY hours) AS median
            FROM (SELECT {HOURS} AS hours FROM shop_order o
                  WHERE {IN_PERIOD} AND o.status IN {DELIVERED} AND o.delivered_at IS NOT NULL) delivered
            """);

    private static final String ZONES = sql("""
            SELECT z.governorate, z.city, z.area, o.currency, count(*) AS delivered,
                   sum(o.delivery_fee) AS fees, avg({HOURS}) AS average
            FROM shop_order o
            LEFT JOIN delivery_zone z ON z.tenant_id = o.tenant_id AND z.id = o.zone_id
            WHERE {IN_PERIOD} AND o.status IN {DELIVERED}
            GROUP BY z.id, z.governorate, z.city, z.area, o.currency
            ORDER BY delivered DESC, z.governorate, z.city, z.area
            """);

    private static final String DRIVER_CASH = sql("""
            SELECT c.driver_id, c.currency, sum(c.amount) AS amount
            FROM cod_collection c
            WHERE c.driver_id IS NOT NULL AND c.collected_at >= :start AND c.collected_at < :end
            GROUP BY 1, 2 ORDER BY 2
            """);

    private static final String DRIVERS = sql("""
            SELECT o.driver_id, s.name,
                   count(*) FILTER (WHERE o.status IN {DELIVERED}) AS delivered,
                   count(*) FILTER (WHERE o.status = 'REFUSED') AS refused,
                   avg({HOURS}) FILTER (WHERE o.status IN {DELIVERED}) AS average
            FROM shop_order o
            JOIN staff_user s ON s.tenant_id = o.tenant_id AND s.id = o.driver_id
            WHERE {IN_PERIOD}
            GROUP BY o.driver_id, s.name
            ORDER BY delivered DESC, s.name
            """);

    /** Buyers in the period, split by whether their first purchase ever falls in it. */
    private static final String BUYERS = sql("""
            WITH first_purchase AS (
                SELECT o.customer_id, min(o.placed_at) AS first_at FROM shop_order o
                WHERE o.status NOT IN {NOT_SOLD}
                GROUP BY o.customer_id),
            buyers AS (SELECT DISTINCT o.customer_id FROM shop_order o WHERE {SOLD})
            SELECT count(*) FILTER (WHERE f.first_at >= :start) AS new_customers,
                   count(*) FILTER (WHERE f.first_at < :start) AS returning
            FROM buyers b JOIN first_purchase f ON f.customer_id = b.customer_id
            """);

    private static final String TOP_CUSTOMERS = sql("""
            SELECT o.customer_id, (array_agg(o.customer_name ORDER BY o.placed_at DESC))[1] AS name,
                   (array_agg(o.customer_phone ORDER BY o.placed_at DESC))[1] AS phone,
                   o.display_currency AS currency, count(*) AS orders, sum(o.total_display) AS spent
            FROM shop_order o
            WHERE {SOLD}
            GROUP BY o.customer_id, o.display_currency
            ORDER BY spent DESC, orders DESC
            LIMIT :rows
            """);

    private static final String METHODS = sql("""
            SELECT o.payment_method, o.display_currency AS currency, count(*) AS orders,
                   sum(o.total_display) AS amount
            FROM shop_order o
            WHERE {SOLD}
            GROUP BY 1, 2
            ORDER BY orders DESC, 1
            """);

    private static final String AWAITING_PROOF = sql("""
            SELECT count(*) FROM shop_order o
            WHERE o.payment_status = 'AWAITING_VERIFICATION' AND o.status NOT IN {NOT_SOLD}
            """);

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CodSettlement settlement;

    StoreReportService(NamedParameterJdbcTemplate jdbc, ObjectMapper json, CodSettlement settlement) {
        this.jdbc = jdbc;
        this.json = json;
        this.settlement = settlement;
    }

    @Override
    public Sales sales(ReportPeriod period) {
        var totals = totals(period);
        var previous = totals(period.previous());
        var found = new HashMap<String, Point>();
        jdbc.query(SALES_POINTS, params(period).addValue("unit", period.unit().sql()), rows -> {
            var point = new Point(rows.getObject("grp", LocalDate.class), currency(rows, "currency"),
                    rows.getLong("orders"), money(rows, "revenue", "currency"));
            found.put(point.group() + "/" + point.currency(), point);
        });
        // Every group of the period for each currency sold in, so days without sales show as empty bars.
        var points = new ArrayList<Point>();
        for (var total : totals) {
            for (var group : period.groups()) {
                points.add(found.getOrDefault(group + "/" + total.currency(),
                        new Point(group, total.currency(), 0, Money.zero(total.currency()))));
            }
        }
        return new Sales(totals, previous, List.copyOf(points));
    }

    private List<Total> totals(ReportPeriod period) {
        return jdbc.query(SALES_TOTALS, params(period), (rows, i) -> {
            var revenue = money(rows, "revenue", "currency");
            var orders = rows.getLong("orders");
            var average = orders == 0 ? null : Money.of(
                    revenue.amount().divide(BigDecimal.valueOf(orders), Money.SCALE, RoundingMode.HALF_UP),
                    revenue.currency());
            return new Total(revenue.currency(), orders, revenue, average);
        });
    }

    @Override
    public Products products(ReportPeriod period) {
        var best = jdbc.query(BEST_SELLERS, params(period).addValue("rows", LIST_ROWS), (rows, i) -> new BestSeller(
                rows.getObject("product_id", UUID.class), text(rows.getString("name")), rows.getLong("quantity"),
                money(rows, "revenue", "currency")));
        var idle = jdbc.query("SELECT p.id, p.name, p.created_at " + IDLE_FROM + " ORDER BY p.created_at, p.id"
                        + " LIMIT :rows", params(period).addValue("rows", LONG_LIST_ROWS),
                (rows, i) -> new Idle(rows.getObject("id", UUID.class), text(rows.getString("name")),
                        rows.getTimestamp("created_at").toInstant()));
        var idleCount = jdbc.queryForObject("SELECT count(*) " + IDLE_FROM, params(period), Long.class);
        var lowParams = new MapSqlParameterSource("low", LOW_STOCK).addValue("rows", LONG_LIST_ROWS);
        var low = jdbc.query(LOW_STOCK_ROWS, lowParams,
                (rows, i) -> new LowStock(rows.getObject("id", UUID.class), text(rows.getString("name")),
                        strings(rows.getString("option_values")), rows.getInt("available")));
        return new Products(best, idle, idleCount == null ? 0 : idleCount, low);
    }

    @Override
    public OrdersAndDelivery ordersAndDelivery(ReportPeriod period) {
        var byStatus = new LinkedHashMap<String, Long>();
        jdbc.query(BY_STATUS, params(period), rows -> {
            byStatus.put(rows.getString("status"), rows.getLong("orders"));
        });
        var placed = byStatus.values().stream().mapToLong(Long::longValue).sum();
        var lost = jdbc.query(LOST, params(period).addValue("rows", LONG_LIST_ROWS), (rows, i) -> new Lost(
                rows.getInt("number"), rows.getString("status"), rows.getTimestamp("placed_at").toInstant(),
                rows.getString("customer_name"), rows.getString("reason")));
        var time = jdbc.queryForObject(DELIVERY_TIME, params(period), (rows, i) -> new DeliveryTime(
                rows.getLong("delivered"), hours(rows, "average"), hours(rows, "median")));
        var zones = jdbc.query(ZONES, params(period), (rows, i) -> new ZoneLine(rows.getString("governorate"),
                rows.getString("city"), rows.getString("area"), rows.getLong("delivered"),
                money(rows, "fees", "currency"), hours(rows, "average")));
        var cash = new HashMap<UUID, List<Money>>();
        jdbc.query(DRIVER_CASH, params(period), rows -> {
            cash.computeIfAbsent(rows.getObject("driver_id", UUID.class), id -> new ArrayList<>())
                    .add(money(rows, "amount", "currency"));
        });
        var drivers = jdbc.query(DRIVERS, params(period), (rows, i) -> {
            var id = rows.getObject("driver_id", UUID.class);
            return new DriverLine(id, rows.getString("name"), rows.getLong("delivered"), rows.getLong("refused"),
                    hours(rows, "average"), List.copyOf(cash.getOrDefault(id, List.of())));
        });
        return new OrdersAndDelivery(placed, Collections.unmodifiableMap(byStatus), lost, time, zones, drivers);
    }

    @Override
    public CustomersAndPayments customersAndPayments(ReportPeriod period) {
        var buyers = jdbc.queryForObject(BUYERS, params(period),
                (rows, i) -> new long[] {rows.getLong("new_customers"), rows.getLong("returning")});
        var top = jdbc.query(TOP_CUSTOMERS, params(period).addValue("rows", LIST_ROWS), (rows, i) -> new TopCustomer(
                rows.getObject("customer_id", UUID.class), rows.getString("name"), rows.getString("phone"),
                rows.getLong("orders"), money(rows, "spent", "currency")));
        var methods = jdbc.query(METHODS, params(period), (rows, i) -> new MethodLine(
                rows.getString("payment_method"), rows.getLong("orders"), money(rows, "amount", "currency")));
        var awaiting = jdbc.queryForObject(AWAITING_PROOF, new MapSqlParameterSource(), Long.class);
        return new CustomersAndPayments(buyers[0], buyers[1], top, methods, awaiting == null ? 0 : awaiting,
                settlement.outstanding());
    }

    /** The query with its {NAME} fragments filled in. */
    static String sql(String query) {
        var result = query;
        for (var fragment : FRAGMENTS.entrySet()) {
            result = result.replace(fragment.getKey(), fragment.getValue());
        }
        return result;
    }

    private static MapSqlParameterSource params(ReportPeriod period) {
        return new MapSqlParameterSource("start", Timestamp.from(period.start()))
                .addValue("end", Timestamp.from(period.end()));
    }

    private static Currency currency(ResultSet rows, String column) throws SQLException {
        return Currency.valueOf(rows.getString(column));
    }

    private static Money money(ResultSet rows, String amount, String currency) throws SQLException {
        var value = rows.getBigDecimal(amount);
        return Money.of(value == null ? BigDecimal.ZERO : value, currency(rows, currency));
    }

    private static Double hours(ResultSet rows, String column) throws SQLException {
        var value = rows.getObject(column);
        return value == null ? null : ((Number) value).doubleValue();
    }

    /** A name as stored ({"ar": ..., "en": ...}). */
    private LocalizedText text(String stored) {
        try {
            var map = json.readValue(stored, new TypeReference<Map<String, String>>() { });
            return LocalizedText.of(map.get("ar"), map.get("en"));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return LocalizedText.of("?", "?");
        }
    }

    private List<String> strings(String stored) {
        try {
            return List.copyOf(json.readValue(stored, new TypeReference<List<String>>() { }));
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
