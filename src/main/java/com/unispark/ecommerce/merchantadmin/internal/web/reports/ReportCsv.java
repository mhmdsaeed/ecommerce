package com.unispark.ecommerce.merchantadmin.internal.web.reports;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import com.unispark.ecommerce.report.ReportPeriod;
import com.unispark.ecommerce.report.StoreReports;
import com.unispark.ecommerce.shared.Money;

/**
 * A report's table as a CSV file: a header row in the admin's language, then plain values (dates as yyyy-MM-dd,
 * amounts without grouping, the currency in its own column) so a spreadsheet can sum them. UTF-8 with a byte order
 * mark, which Excel needs to read Arabic; cells are quoted when they hold a comma, quote or line break.
 */
@Component
class ReportCsv {

    /** Each report's tables; the first is the one a link without {@code table} gets. */
    static final Map<String, List<String>> TABLES = Map.of(
            "sales", List.of("days"),
            "products", List.of("best", "idle", "stock"),
            "orders", List.of("status", "lost", "zones", "drivers"),
            "customers", List.of("top", "methods"));

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ReportPeriod.ZONE);

    private final StoreReports reports;
    private final MessageSource messages;

    ReportCsv(StoreReports reports, MessageSource messages) {
        this.reports = reports;
        this.messages = messages;
    }

    record File(String name, byte[] bytes) {
    }

    Optional<File> table(String report, String table, ReportPeriod period, Locale locale) {
        var tables = TABLES.get(report);
        if (tables == null) {
            return Optional.empty();
        }
        var chosen = table == null || table.isBlank() ? tables.getFirst() : table;
        if (!tables.contains(chosen)) {
            return Optional.empty();
        }
        var rows = rows(report + "/" + chosen, period, locale);
        return Optional.of(new File(report + "-" + chosen, encode(rows)));
    }

    private List<List<String>> rows(String table, ReportPeriod period, Locale locale) {
        var rows = new ArrayList<List<String>>();
        switch (table) {
            case "sales/days" -> {
                rows.add(header(locale, "date", "currency", "orders", "revenue"));
                reports.sales(period).points().forEach(point -> rows.add(List.of(point.group().toString(),
                        point.currency().name(), String.valueOf(point.orders()), amount(point.revenue()))));
            }
            case "products/best" -> {
                rows.add(header(locale, "product", "quantity", "currency", "revenue"));
                reports.products(period).bestSellers().forEach(line -> rows.add(List.of(line.name().in(locale),
                        String.valueOf(line.quantity()), line.revenue().currency().name(), amount(line.revenue()))));
            }
            case "products/idle" -> {
                rows.add(header(locale, "product", "since"));
                reports.products(period).idle().forEach(line -> rows.add(List.of(line.name().in(locale),
                        TIME.format(line.since()))));
            }
            case "products/stock" -> {
                rows.add(header(locale, "product", "options", "available"));
                reports.products(period).lowStock().forEach(line -> rows.add(List.of(line.name().in(locale),
                        String.join(" / ", line.options()), String.valueOf(line.available()))));
            }
            case "orders/status" -> {
                rows.add(header(locale, "status", "orders"));
                reports.ordersAndDelivery(period).byStatus().forEach((status, count) -> rows.add(List.of(
                        message("order.status." + status, locale), String.valueOf(count))));
            }
            case "orders/lost" -> {
                rows.add(header(locale, "order", "status", "placed", "customer", "reason"));
                reports.ordersAndDelivery(period).lost().forEach(line -> rows.add(List.of(
                        String.valueOf(line.number()), message("order.status." + line.status(), locale),
                        TIME.format(line.placedAt()), line.customerName(),
                        line.reason() == null ? "" : line.reason())));
            }
            case "orders/zones" -> {
                rows.add(header(locale, "governorate", "city", "area", "delivered", "currency", "fees", "hours"));
                reports.ordersAndDelivery(period).zones().forEach(line -> rows.add(List.of(
                        line.governorate() == null ? "" : message("governorate." + line.governorate(), locale),
                        blank(line.city()), blank(line.area()), String.valueOf(line.delivered()),
                        line.fees().currency().name(), amount(line.fees()), hours(line.averageHours()))));
            }
            case "orders/drivers" -> {
                rows.add(header(locale, "driver", "delivered", "refused", "hours", "cash"));
                reports.ordersAndDelivery(period).drivers().forEach(line -> rows.add(List.of(line.name(),
                        String.valueOf(line.delivered()), String.valueOf(line.refused()), hours(line.averageHours()),
                        line.cash().stream().map(money -> amount(money) + " " + money.currency())
                                .collect(Collectors.joining("; ")))));
            }
            case "customers/top" -> {
                rows.add(header(locale, "customer", "phone", "orders", "currency", "spent"));
                reports.customersAndPayments(period).top().forEach(line -> rows.add(List.of(blank(line.name()),
                        blank(line.phone()), String.valueOf(line.orders()), line.spent().currency().name(),
                        amount(line.spent()))));
            }
            case "customers/methods" -> {
                rows.add(header(locale, "method", "orders", "currency", "amount"));
                reports.customersAndPayments(period).methods().forEach(line -> rows.add(List.of(
                        message("payment.method." + line.method(), locale), String.valueOf(line.orders()),
                        line.amount().currency().name(), amount(line.amount()))));
            }
            default -> throw new IllegalArgumentException("No table " + table);
        }
        return rows;
    }

    private List<String> header(Locale locale, String... columns) {
        var header = new ArrayList<String>();
        for (var column : columns) {
            header.add(message("report.csv." + column, locale));
        }
        return header;
    }

    private String message(String key, Locale locale) {
        return messages.getMessage(key, null, key, locale);
    }

    private static String amount(Money money) {
        return money.amount().stripTrailingZeros().toPlainString();
    }

    private static String hours(Double hours) {
        return hours == null ? "" : String.format(Locale.ROOT, "%.1f", hours);
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    static byte[] encode(List<List<String>> rows) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        var text = rows.stream()
                .map(row -> row.stream().map(ReportCsv::cell).collect(Collectors.joining(",")))
                .collect(Collectors.joining("\r\n", "", "\r\n"));
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /**
     * Quoted when needed. A cell starting with =, +, - or @ gets a leading apostrophe, so a spreadsheet shows it as
     * text instead of running it as a formula (customer names and reasons are typed by shoppers and staff).
     */
    static String cell(String value) {
        var text = value;
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0 && !isNumber(text)) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private static boolean isNumber(String text) {
        return text.matches("-?\\d+(\\.\\d+)?");
    }
}
