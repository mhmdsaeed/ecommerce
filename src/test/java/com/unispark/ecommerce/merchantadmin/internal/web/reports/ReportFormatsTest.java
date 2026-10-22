package com.unispark.ecommerce.merchantadmin.internal.web.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.unispark.ecommerce.report.ReportPeriod.Unit;
import com.unispark.ecommerce.report.StoreReports.Point;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.Money;

class ReportFormatsTest {

    @Test
    void roundsTheChartTopToOneTwoOrFive() {
        assertThat(ColumnChart.niceTop(new BigDecimal("0"))).isEqualByComparingTo("0");
        assertThat(ColumnChart.niceTop(new BigDecimal("830"))).isEqualByComparingTo("1000");
        assertThat(ColumnChart.niceTop(new BigDecimal("1000"))).isEqualByComparingTo("1000");
        assertThat(ColumnChart.niceTop(new BigDecimal("1250"))).isEqualByComparingTo("2000");
        assertThat(ColumnChart.niceTop(new BigDecimal("312500.50"))).isEqualByComparingTo("500000");
    }

    @Test
    void drawsOneChartPerCurrencyWithAFewLabels() {
        var points = new ArrayList<Point>();
        for (int day = 1; day <= 30; day++) {
            points.add(new Point(LocalDate.of(2026, 9, day), Currency.SYP, day % 3, syp(day * 1000)));
        }
        points.add(new Point(LocalDate.of(2026, 9, 1), Currency.USD, 1, Money.of(new BigDecimal("12"), Currency.USD)));

        var charts = ColumnChart.of(points, Unit.DAY, Locale.ENGLISH);

        assertThat(charts).extracting(ColumnChart::currency).containsExactly(Currency.SYP, Currency.USD);
        var pounds = charts.getFirst();
        assertThat(pounds.top()).isEqualTo(syp(50_000));
        assertThat(pounds.columns().getLast().percent()).isEqualTo(60);
        assertThat(pounds.columns().stream().filter(ColumnChart.Column::labelled).count()).isBetween(8L, 9L);
    }

    @Test
    void writesCsvExcelReadsInArabic() {
        var bytes = ReportCsv.encode(List.of(List.of("المنتج", "الكمية"), List.of("قميص, أحمر", "3")));

        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8))
                .isEqualTo("المنتج,الكمية\r\n\"قميص, أحمر\",3\r\n");
    }

    @Test
    void keepsTypedTextFromRunningAsAFormula() {
        assertThat(ReportCsv.cell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(ReportCsv.cell("+963933111001")).isEqualTo("'+963933111001");
        assertThat(ReportCsv.cell("-15")).isEqualTo("-15");
        assertThat(ReportCsv.cell("قال \"لا\"")).isEqualTo("\"قال \"\"لا\"\"\"");
    }

    private static Money syp(long amount) {
        return Money.of(BigDecimal.valueOf(amount), Currency.SYP);
    }
}
