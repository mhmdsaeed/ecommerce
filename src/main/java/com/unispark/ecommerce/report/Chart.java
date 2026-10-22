package com.unispark.ecommerce.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import com.unispark.ecommerce.report.ReportPeriod.Unit;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.Money;

/**
 * A column chart over a report's period (fragments/report.html draws it): amounts in one currency (sales, payments),
 * or plain counts (signups) when {@code currency} is null. Amounts in two currencies never share a chart. Heights are
 * shares of a rounded top value (1, 2 or 5 times a power of ten), which the axis labels with its half. Only some
 * columns are labelled underneath, spaced back from the newest, about eight per chart.
 */
public record Chart(Currency currency, BigDecimal top, BigDecimal half, List<Column> columns) {

    static final int LABELS = 8;

    /**
     * @param label   short, under the column (shown when {@code labelled})
     * @param detail  the full date, for the tooltip and the table
     * @param count   orders, payments or signups in the column
     * @param value   the amount, or the count again for a chart of counts
     * @param percent the column's height, 0 to 100
     * @param minor   a label a phone leaves out: every other one, counting back from the newest
     */
    public record Column(String label, String detail, long count, BigDecimal value, int percent, boolean labelled,
            boolean minor, Currency currency) {

        /** The amount with its currency; null on a chart of counts. */
        public Money money() {
            return currency == null ? null : Money.of(value, currency);
        }
    }

    /** One group's figures: an amount in a currency, or a count alone ({@code currency} and {@code value} null). */
    public record Entry(LocalDate group, Currency currency, long count, BigDecimal value) {
    }

    /** One chart per currency, in the order the currencies first appear. */
    public static List<Chart> amounts(List<Entry> entries, Unit unit, Locale locale) {
        var byCurrency = new LinkedHashMap<Currency, List<Entry>>();
        entries.forEach(entry -> byCurrency.computeIfAbsent(entry.currency(), currency -> new ArrayList<>())
                .add(entry));
        var charts = new ArrayList<Chart>();
        byCurrency.forEach((currency, series) -> charts.add(chart(currency, series, unit, locale)));
        return List.copyOf(charts);
    }

    /** A chart of counts; the entries' currency and value are ignored. */
    public static Chart counts(List<Entry> entries, Unit unit, Locale locale) {
        return chart(null, entries.stream()
                .map(entry -> new Entry(entry.group(), null, entry.count(), BigDecimal.valueOf(entry.count())))
                .toList(), unit, locale);
    }

    private static Chart chart(Currency currency, List<Entry> series, Unit unit, Locale locale) {
        var max = series.stream().map(Entry::value).reduce(BigDecimal.ZERO, BigDecimal::max);
        var top = niceTop(max);
        var every = Math.max(1, (int) Math.ceil(series.size() / (double) LABELS));
        var shortFormat = DateTimeFormatter.ofPattern(unit == Unit.MONTH ? "MMM" : "d/M").withLocale(locale);
        var longFormat = unit == Unit.MONTH
                ? DateTimeFormatter.ofPattern("MMMM yyyy").withLocale(locale)
                : DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale);
        var columns = new ArrayList<Column>();
        for (int i = 0; i < series.size(); i++) {
            var entry = series.get(i);
            var percent = top.signum() == 0 ? 0 : entry.value().multiply(BigDecimal.valueOf(100))
                    .divide(top, 0, RoundingMode.HALF_UP).intValue();
            var back = series.size() - 1 - i;
            columns.add(new Column(shortFormat.format(entry.group()), longFormat.format(entry.group()),
                    entry.count(), entry.value(), percent, back % every == 0, back % (2 * every) == every, currency));
        }
        var half = top.divide(BigDecimal.valueOf(2), currency == null ? 1 : Money.SCALE, RoundingMode.HALF_UP);
        return new Chart(currency, top, half, List.copyOf(columns));
    }

    /** The smallest 1, 2 or 5 times a power of ten that is at least {@code max}; zero for zero. */
    public static BigDecimal niceTop(BigDecimal max) {
        if (max.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        var power = BigDecimal.ONE.scaleByPowerOfTen(max.setScale(0, RoundingMode.CEILING).precision() - 1);
        for (var step : new int[] {1, 2, 5, 10}) {
            var candidate = power.multiply(BigDecimal.valueOf(step));
            if (candidate.compareTo(max) >= 0) {
                return candidate.setScale(0, RoundingMode.UNNECESSARY);
            }
        }
        return power.multiply(BigDecimal.TEN);
    }

    /** The top as money; null on a chart of counts. */
    public Money topMoney() {
        return currency == null ? null : Money.of(top, currency);
    }

    public Money halfMoney() {
        return currency == null ? null : Money.of(half, currency);
    }

    /** Nothing in any column: the chart says so instead of drawing empty columns. */
    public boolean empty() {
        return top.signum() == 0;
    }
}
