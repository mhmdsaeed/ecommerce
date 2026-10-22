package com.unispark.ecommerce.merchantadmin.internal.web.reports;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import com.unispark.ecommerce.report.ReportPeriod.Unit;
import com.unispark.ecommerce.report.StoreReports.Point;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.Money;

/**
 * Sales over time as columns, one chart per currency (amounts are never converted, and two currencies never share
 * an axis). Heights are shares of a rounded top value (1, 2 or 5 times a power of ten), which the axis labels with
 * its half. Only some columns are labelled underneath, so labels never crowd: about eight per chart.
 */
record ColumnChart(Currency currency, Money top, Money half, List<Column> columns) {

    static final int LABELS = 8;

    /**
     * @param label   short, under the column (shown when {@code labelled})
     * @param detail  the full date, for the tooltip and the table
     * @param percent the column's height, 0 to 100
     */
    record Column(String label, String detail, long orders, Money revenue, int percent, boolean labelled) {
    }

    static List<ColumnChart> of(List<Point> points, Unit unit, Locale locale) {
        var byCurrency = new LinkedHashMap<Currency, List<Point>>();
        points.forEach(point -> byCurrency.computeIfAbsent(point.currency(), currency -> new ArrayList<>()).add(point));
        var charts = new ArrayList<ColumnChart>();
        byCurrency.forEach((currency, series) -> charts.add(chart(currency, series, unit, locale)));
        return List.copyOf(charts);
    }

    private static ColumnChart chart(Currency currency, List<Point> series, Unit unit, Locale locale) {
        var max = series.stream().map(point -> point.revenue().amount()).reduce(BigDecimal.ZERO, BigDecimal::max);
        var top = niceTop(max);
        var every = Math.max(1, (int) Math.ceil(series.size() / (double) LABELS));
        var shortFormat = DateTimeFormatter.ofPattern(unit == Unit.MONTH ? "MMM" : "d/M").withLocale(locale);
        var longFormat = unit == Unit.MONTH
                ? DateTimeFormatter.ofPattern("MMMM yyyy").withLocale(locale)
                : DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale);
        var columns = new ArrayList<Column>();
        for (int i = 0; i < series.size(); i++) {
            var point = series.get(i);
            var percent = top.signum() == 0 ? 0 : point.revenue().amount().multiply(BigDecimal.valueOf(100))
                    .divide(top, 0, RoundingMode.HALF_UP).intValue();
            columns.add(new Column(shortFormat.format(point.group()), longFormat.format(point.group()),
                    point.orders(), point.revenue(), percent, (series.size() - 1 - i) % every == 0));
        }
        return new ColumnChart(currency, Money.of(top, currency),
                Money.of(top.divide(BigDecimal.valueOf(2), Money.SCALE, RoundingMode.HALF_UP), currency),
                List.copyOf(columns));
    }

    /** The smallest 1, 2 or 5 times a power of ten that is at least {@code max}; zero for zero. */
    static BigDecimal niceTop(BigDecimal max) {
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

    /** Whether any column has sales: a chart of only empty columns says so instead. */
    public boolean empty() {
        return top.amount().signum() == 0;
    }
}
