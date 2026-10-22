package com.unispark.ecommerce.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.unispark.ecommerce.report.Chart.Entry;
import com.unispark.ecommerce.report.ReportPeriod.Unit;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.Money;

class ChartAndCsvTest {

    @Test
    void roundsTheChartTopToOneTwoOrFive() {
        assertThat(Chart.niceTop(new BigDecimal("0"))).isEqualByComparingTo("0");
        assertThat(Chart.niceTop(new BigDecimal("830"))).isEqualByComparingTo("1000");
        assertThat(Chart.niceTop(new BigDecimal("1000"))).isEqualByComparingTo("1000");
        assertThat(Chart.niceTop(new BigDecimal("1250"))).isEqualByComparingTo("2000");
        assertThat(Chart.niceTop(new BigDecimal("312500.50"))).isEqualByComparingTo("500000");
        assertThat(Chart.niceTop(new BigDecimal("3"))).isEqualByComparingTo("5");
    }

    @Test
    void drawsOneChartPerCurrencyWithAFewLabels() {
        var entries = new ArrayList<Entry>();
        for (int day = 1; day <= 30; day++) {
            entries.add(new Entry(LocalDate.of(2026, 9, day), Currency.SYP, day % 3, BigDecimal.valueOf(day * 1000L)));
        }
        entries.add(new Entry(LocalDate.of(2026, 9, 1), Currency.USD, 1, new BigDecimal("12")));

        var charts = Chart.amounts(entries, Unit.DAY, Locale.ENGLISH);

        assertThat(charts).extracting(Chart::currency).containsExactly(Currency.SYP, Currency.USD);
        var pounds = charts.getFirst();
        assertThat(pounds.topMoney()).isEqualTo(Money.of(new BigDecimal("50000"), Currency.SYP));
        assertThat(pounds.columns().getLast().percent()).isEqualTo(60);
        // Labels are spaced back from the newest column, which always has one.
        assertThat(pounds.columns().getLast().labelled()).isTrue();
        assertThat(pounds.columns().stream().filter(Chart.Column::labelled).count()).isEqualTo(8);
    }

    @Test
    void chartsCountsWithoutACurrency() {
        var chart = Chart.counts(List.of(new Entry(LocalDate.of(2026, 10, 1), null, 3, null),
                new Entry(LocalDate.of(2026, 10, 2), null, 0, null)), Unit.DAY, Locale.ENGLISH);

        assertThat(chart.currency()).isNull();
        assertThat(chart.top()).isEqualByComparingTo("5");
        assertThat(chart.half()).isEqualByComparingTo("2.5");
        assertThat(chart.topMoney()).isNull();
        assertThat(chart.columns()).extracting(Chart.Column::percent).containsExactly(60, 0);
    }

    @Test
    void writesCsvExcelReadsInArabic() {
        var bytes = Csv.encode(List.of(List.of("المنتج", "الكمية"), List.of("قميص, أحمر", "3")));

        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8))
                .isEqualTo("المنتج,الكمية\r\n\"قميص, أحمر\",3\r\n");
    }

    @Test
    void keepsTypedTextFromRunningAsAFormula() {
        assertThat(Csv.cell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(Csv.cell("+963933111001")).isEqualTo("'+963933111001");
        assertThat(Csv.cell("-15")).isEqualTo("-15");
        assertThat(Csv.cell(null)).isEmpty();
    }
}
