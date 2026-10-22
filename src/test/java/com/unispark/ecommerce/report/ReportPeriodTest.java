package com.unispark.ecommerce.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.unispark.ecommerce.report.ReportPeriod.Unit;

class ReportPeriodTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);

    @Test
    void readsThePresets() {
        assertThat(ReportPeriod.of("today", null, null, TODAY)).isEqualTo(new ReportPeriod("today", TODAY, TODAY));
        assertThat(ReportPeriod.of("7d", null, null, TODAY).from()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(ReportPeriod.of("month", null, null, TODAY).from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(ReportPeriod.of("last-month", null, null, TODAY))
                .isEqualTo(new ReportPeriod("last-month", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)));
    }

    @Test
    void fallsBackToTheLast30DaysForAnythingElse() {
        for (var preset : new String[] {null, "", "forever", "custom"}) {
            var period = ReportPeriod.of(preset, null, null, TODAY);

            assertThat(period.preset()).as(preset).isEqualTo("30d");
            assertThat(period.days()).isEqualTo(30);
        }
        assertThat(ReportPeriod.of("custom", "2026-10-01", "not a date", TODAY).preset()).isEqualTo("30d");
    }

    @Test
    void keepsCustomRangesInsideTodayAndAYear() {
        assertThat(ReportPeriod.of("custom", "2026-10-01", "2026-12-31", TODAY).to()).isEqualTo(TODAY);
        assertThat(ReportPeriod.of("custom", "2026-10-25", "2026-10-30", TODAY))
                .isEqualTo(new ReportPeriod("custom", TODAY, TODAY));
        assertThat(ReportPeriod.of("custom", "2020-01-01", "2026-10-21", TODAY).days())
                .isEqualTo(ReportPeriod.MAX_DAYS);
    }

    @Test
    void comparesWithAsManyDaysJustBefore() {
        var period = ReportPeriod.of("7d", null, null, TODAY);

        assertThat(period.previous().from()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(period.previous().to()).isEqualTo(LocalDate.of(2026, 10, 14));
    }

    @Test
    void groupsByDayWeekOrMonthWithNoGaps() {
        assertThat(ReportPeriod.of("30d", null, null, TODAY).unit()).isEqualTo(Unit.DAY);
        assertThat(ReportPeriod.of("30d", null, null, TODAY).groups()).hasSize(30);
        var quarter = ReportPeriod.of("custom", "2026-08-01", "2026-10-21", TODAY);
        assertThat(quarter.unit()).isEqualTo(Unit.WEEK);
        // Weeks start on Monday, like date_trunc: 1 August 2026 is a Saturday.
        assertThat(quarter.groups().getFirst()).isEqualTo(LocalDate.of(2026, 7, 27));
        var year = ReportPeriod.of("custom", "2025-11-01", "2026-10-21", TODAY);
        assertThat(year.unit()).isEqualTo(Unit.MONTH);
        assertThat(year.groups()).hasSize(12).startsWith(LocalDate.of(2025, 11, 1));
    }

    @Test
    void coversWholeDaysInDamascusTime() {
        var period = ReportPeriod.of("today", null, null, TODAY);

        assertThat(period.start().toString()).isEqualTo("2026-10-20T21:00:00Z");
        assertThat(period.end().toString()).isEqualTo("2026-10-21T21:00:00Z");
    }
}
