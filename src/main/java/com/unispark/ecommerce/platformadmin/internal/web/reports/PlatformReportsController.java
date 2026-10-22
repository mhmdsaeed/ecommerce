package com.unispark.ecommerce.platformadmin.internal.web.reports;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.MessageSource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import com.unispark.ecommerce.report.Chart;
import com.unispark.ecommerce.report.Csv;
import com.unispark.ecommerce.report.ReportPeriod;
import com.unispark.ecommerce.shared.Money;

/**
 * The platform owner's reports (platform admins only, like everything under /platform): revenue, stores, activity
 * across stores (totals only) and operations. Same period picker, CSV and print as the store reports.
 */
@Controller
@RequestMapping("/platform/reports")
class PlatformReportsController {

    static final List<String> REPORTS = List.of("revenue", "stores", "activity", "operations");
    /** Each report's tables for CSV; the first is the one a link without {@code table} gets. */
    static final Map<String, List<String>> TABLES = Map.of(
            "revenue", List.of("plans", "invoices", "payments"),
            "stores", List.of("signups", "trials"),
            "activity", List.of("busiest", "quiet"),
            "operations", List.of("messages", "jobs", "storage"));

    private final PlatformReportQueries queries;
    private final MessageSource messages;
    private final Clock clock;

    PlatformReportsController(PlatformReportQueries queries, MessageSource messages, Clock clock) {
        this.queries = queries;
        this.messages = messages;
        this.clock = clock;
    }

    @GetMapping
    String index() {
        return "redirect:/platform/reports/revenue";
    }

    @GetMapping("/{report:revenue|stores|activity|operations}")
    String report(@PathVariable String report, @RequestParam(required = false) String period,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to, Locale locale,
            Model model) {
        var chosen = period(period, from, to);
        model.addAttribute("report", report);
        model.addAttribute("reports", REPORTS);
        model.addAttribute("reportBase", "/platform/reports");
        model.addAttribute("reportKeys", "platform.report");
        model.addAttribute("period", chosen);
        model.addAttribute("presets", ReportPeriod.PRESETS);
        model.addAttribute("periodQuery", "custom".equals(chosen.preset())
                ? "period=custom&from=" + chosen.from() + "&to=" + chosen.to() : "period=" + chosen.preset());
        switch (report) {
            case "revenue" -> {
                var revenue = queries.revenue(chosen, today());
                model.addAttribute("revenue", revenue);
                model.addAttribute("charts", Chart.amounts(revenue.payments(), chosen.unit(), locale));
            }
            case "stores" -> {
                var stores = queries.stores(chosen, today());
                model.addAttribute("stores", stores);
                model.addAttribute("chart", Chart.counts(stores.signups(), chosen.unit(), locale));
                model.addAttribute("trialsAhead", PlatformReportQueries.TRIALS_AHEAD);
            }
            case "activity" -> {
                var activity = queries.activity(chosen, clock.instant());
                model.addAttribute("activity", activity);
                model.addAttribute("charts", Chart.amounts(activity.points(), chosen.unit(), locale));
                model.addAttribute("quietDays", PlatformReportQueries.QUIET_DAYS);
            }
            default -> model.addAttribute("operations", queries.operations(chosen, clock.instant()));
        }
        return "platformadmin/reports/" + report;
    }

    @GetMapping("/{report:revenue|stores|activity|operations}.csv")
    void download(@PathVariable String report, @RequestParam(required = false) String table,
            @RequestParam(required = false) String period, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, Locale locale, HttpServletResponse response)
            throws IOException {
        var tables = TABLES.get(report);
        var chosen = table == null || table.isBlank() ? tables.getFirst() : table;
        if (!tables.contains(chosen)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        var range = period(period, from, to);
        var bytes = Csv.encode(rows(report + "/" + chosen, range, locale));
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("platform-" + report + "-" + chosen + "-" + range.from() + "-" + range.to() + ".csv",
                        StandardCharsets.UTF_8)
                .build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getOutputStream().write(bytes);
    }

    private List<List<String>> rows(String table, ReportPeriod period, Locale locale) {
        var rows = new ArrayList<List<String>>();
        switch (table) {
            case "revenue/plans" -> {
                rows.add(header(locale, "plan", "paying", "trials", "currency", "monthly"));
                queries.revenue(period, today()).plans().forEach(line -> rows.add(List.of(line.name().in(locale),
                        String.valueOf(line.paying()), String.valueOf(line.trials()), "USD",
                        amount(line.monthly()))));
            }
            case "revenue/invoices" -> {
                rows.add(header(locale, "status", "invoices", "currency", "amount"));
                queries.revenue(period, today()).invoices().forEach(line -> rows.add(List.of(
                        message("billing.invoice.status." + line.status(), locale), String.valueOf(line.count()),
                        line.amount().currency().name(), amount(line.amount()))));
            }
            case "revenue/payments" -> {
                rows.add(header(locale, "date", "payments", "currency", "amount"));
                queries.revenue(period, today()).payments().forEach(entry -> rows.add(List.of(
                        entry.group().toString(), String.valueOf(entry.count()), entry.currency().name(),
                        entry.value().stripTrailingZeros().toPlainString())));
            }
            case "stores/signups" -> {
                rows.add(header(locale, "date", "stores"));
                queries.stores(period, today()).signups().forEach(entry -> rows.add(List.of(
                        entry.group().toString(), String.valueOf(entry.count()))));
            }
            case "stores/trials" -> {
                rows.add(header(locale, "store", "address", "plan", "ends"));
                queries.stores(period, today()).trialsEnding().forEach(line -> rows.add(List.of(
                        line.name().in(locale), line.slug(), line.plan(), line.ends().toString())));
            }
            case "activity/busiest" -> {
                rows.add(header(locale, "store", "address", "orders", "sales"));
                queries.activity(period, clock.instant()).busiest().forEach(line -> rows.add(List.of(
                        line.name().in(locale), line.slug(), String.valueOf(line.orders()),
                        line.sales().stream().map(money -> amount(money) + " " + money.currency())
                                .collect(Collectors.joining("; ")))));
            }
            case "activity/quiet" -> {
                rows.add(header(locale, "store", "address", "last-order", "created"));
                queries.activity(period, clock.instant()).quiet().forEach(line -> rows.add(List.of(
                        line.name().in(locale), line.slug(),
                        line.lastOrderAt() == null ? "" : line.lastOrderAt().toString(), line.createdAt().toString())));
            }
            case "operations/messages" -> {
                rows.add(header(locale, "channel", "sent", "failed", "pending"));
                queries.operations(period, clock.instant()).messages().forEach(line -> rows.add(List.of(
                        line.channel(), String.valueOf(line.sent()), String.valueOf(line.failed()),
                        String.valueOf(line.pending()))));
            }
            case "operations/jobs" -> {
                rows.add(header(locale, "state", "jobs"));
                queries.operations(period, clock.instant()).jobs().forEach((state, count) -> rows.add(List.of(
                        state, String.valueOf(count))));
            }
            case "operations/storage" -> {
                rows.add(header(locale, "store", "address", "files", "bytes"));
                var storage = queries.operations(period, clock.instant()).storage();
                if (storage != null) {
                    storage.forEach(line -> rows.add(List.of(line.name().in(locale), line.slug(),
                            String.valueOf(line.objects()), String.valueOf(line.bytes()))));
                }
            }
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return rows;
    }

    private List<String> header(Locale locale, String... columns) {
        var header = new ArrayList<String>();
        for (var column : columns) {
            header.add(message("platform.report.csv." + column, locale));
        }
        return header;
    }

    private String message(String key, Locale locale) {
        return messages.getMessage(key, null, key, locale);
    }

    private static String amount(Money money) {
        return money.amount().stripTrailingZeros().toPlainString();
    }

    private ReportPeriod period(String period, String from, String to) {
        return ReportPeriod.of(period, from, to, today());
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ReportPeriod.ZONE));
    }
}
