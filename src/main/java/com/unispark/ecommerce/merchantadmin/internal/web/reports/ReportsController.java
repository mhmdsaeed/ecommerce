package com.unispark.ecommerce.merchantadmin.internal.web.reports;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import com.unispark.ecommerce.report.ReportPeriod;
import com.unispark.ecommerce.report.StoreReports;
import com.unispark.ecommerce.report.StoreReports.Total;
import com.unispark.ecommerce.shared.Currency;

/**
 * The store's reports (owners and managers: they show the store's money): sales, products, orders and delivery,
 * customers and payments. Each covers a period from the picker at the top, prints cleanly, and downloads its tables
 * as CSV ({@code /admin/reports/<report>.csv?table=...}) that Excel opens with Arabic intact.
 */
@Controller
@RequestMapping("/admin/reports")
@PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
class ReportsController {

    static final List<String> REPORTS = List.of("sales", "products", "orders", "customers");

    private final StoreReports reports;
    private final ReportCsv csv;
    private final Clock clock;

    ReportsController(StoreReports reports, ReportCsv csv, Clock clock) {
        this.reports = reports;
        this.csv = csv;
        this.clock = clock;
    }

    @GetMapping
    String index() {
        return "redirect:/admin/reports/sales";
    }

    @GetMapping("/{report:sales|products|orders|customers}")
    String report(@PathVariable String report, @RequestParam(required = false) String period,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to, Locale locale,
            Model model) {
        var chosen = period(period, from, to);
        model.addAttribute("report", report);
        model.addAttribute("reports", REPORTS);
        model.addAttribute("period", chosen);
        model.addAttribute("presets", ReportPeriod.PRESETS);
        model.addAttribute("periodQuery", query(chosen));
        switch (report) {
            case "sales" -> {
                var sales = reports.sales(chosen);
                model.addAttribute("sales", sales);
                model.addAttribute("changes", changes(sales.totals(), sales.previous()));
                model.addAttribute("charts", ColumnChart.of(sales.points(), chosen.unit(), locale));
            }
            case "products" -> model.addAttribute("products", reports.products(chosen));
            case "orders" -> {
                var orders = reports.ordersAndDelivery(chosen);
                model.addAttribute("orders", orders);
                model.addAttribute("statusBars", Bars.of(orders.byStatus()));
            }
            default -> {
                var customers = reports.customersAndPayments(chosen);
                model.addAttribute("customers", customers);
                model.addAttribute("maxMethodOrders", customers.methods().stream()
                        .mapToLong(StoreReports.MethodLine::orders).max().orElse(0));
            }
        }
        model.addAttribute("lowStock", StoreReports.LOW_STOCK);
        return "merchantadmin/reports/" + report;
    }

    @GetMapping("/{report:sales|products|orders|customers}.csv")
    void download(@PathVariable String report, @RequestParam(required = false) String table,
            @RequestParam(required = false) String period, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, Locale locale, HttpServletResponse response)
            throws IOException {
        var chosen = period(period, from, to);
        var file = csv.table(report, table, chosen, locale)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(file.name() + "-" + chosen.from() + "-" + chosen.to() + ".csv", StandardCharsets.UTF_8)
                .build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getOutputStream().write(file.bytes());
    }

    private ReportPeriod period(String period, String from, String to) {
        return ReportPeriod.of(period, from, to, LocalDate.now(clock.withZone(ReportPeriod.ZONE)));
    }

    /** The period as query parameters, for links between reports and to the CSV files. */
    static String query(ReportPeriod period) {
        return "custom".equals(period.preset())
                ? "period=custom&from=" + period.from() + "&to=" + period.to()
                : "period=" + period.preset();
    }

    /**
     * Revenue against the period before, per currency: the percentage up or down, or null when the period before
     * sold nothing in that currency (nothing to compare with).
     */
    static Map<Currency, Integer> changes(List<Total> totals, List<Total> previous) {
        var before = previous.stream().collect(Collectors.toMap(Total::currency, Function.identity()));
        var changes = new LinkedHashMap<Currency, Integer>();
        for (var total : totals) {
            var earlier = before.get(total.currency());
            if (earlier == null || earlier.revenue().amount().signum() == 0) {
                changes.put(total.currency(), null);
                continue;
            }
            var change = total.revenue().amount().subtract(earlier.revenue().amount())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(earlier.revenue().amount(), 0, RoundingMode.HALF_UP);
            changes.put(total.currency(), change.intValue());
        }
        return changes;
    }

    /** A count with a bar whose length is its share of the largest count, longest first. */
    record Bars(String key, long count, int percent) {

        static List<Bars> of(Map<String, Long> counts) {
            var max = counts.values().stream().mapToLong(Long::longValue).max().orElse(0);
            var bars = new ArrayList<Bars>();
            counts.forEach((key, count) -> bars.add(new Bars(key, count,
                    max == 0 ? 0 : (int) Math.max(1, Math.round(count * 100.0 / max)))));
            bars.sort((one, other) -> Long.compare(other.count(), one.count()));
            return List.copyOf(bars);
        }
    }
}
