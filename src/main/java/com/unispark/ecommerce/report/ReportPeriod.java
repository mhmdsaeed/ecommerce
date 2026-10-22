package com.unispark.ecommerce.report;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The days a report covers, from and to inclusive, in Damascus time (SPEC 11). Picked as a preset (today, the last 7
 * or 30 days, this or last month) or as a custom range of at most {@link #MAX_DAYS} days.
 *
 * @param preset today, 7d, 30d, month, last-month or custom
 */
public record ReportPeriod(String preset, LocalDate from, LocalDate to) {

    public static final ZoneId ZONE = ZoneId.of("Asia/Damascus");
    public static final List<String> PRESETS = List.of("today", "7d", "30d", "month", "last-month");
    public static final String DEFAULT_PRESET = "30d";
    public static final int MAX_DAYS = 366;

    public ReportPeriod {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("A period ends after it starts: " + from + " to " + to);
        }
    }

    /**
     * The period a request asks for; the last 30 days when it asks for nothing usable. A custom range needs both
     * dates; one ending after today ends today, and one longer than a year keeps its last year.
     */
    public static ReportPeriod of(String preset, String from, String to, LocalDate today) {
        if ("custom".equals(preset)) {
            var start = date(from);
            var end = date(to);
            if (start.isPresent() && end.isPresent()) {
                var last = end.get().isAfter(today) ? today : end.get();
                var first = start.get().isAfter(last) ? last : start.get();
                if (ChronoUnit.DAYS.between(first, last) >= MAX_DAYS) {
                    first = last.minusDays(MAX_DAYS - 1);
                }
                return new ReportPeriod("custom", first, last);
            }
        }
        return switch (preset == null ? DEFAULT_PRESET : preset) {
            case "today" -> new ReportPeriod("today", today, today);
            case "7d" -> new ReportPeriod("7d", today.minusDays(6), today);
            case "month" -> new ReportPeriod("month", today.withDayOfMonth(1), today);
            case "last-month" -> {
                var first = today.minusMonths(1).withDayOfMonth(1);
                yield new ReportPeriod("last-month", first, first.with(TemporalAdjusters.lastDayOfMonth()));
            }
            default -> new ReportPeriod("30d", today.minusDays(29), today);
        };
    }

    public long days() {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }

    /** The first moment of {@code from}. */
    public Instant start() {
        return from.atStartOfDay(ZONE).toInstant();
    }

    /** The first moment after {@code to} (exclusive). */
    public Instant end() {
        return to.plusDays(1).atStartOfDay(ZONE).toInstant();
    }

    /** As many days, just before this period: what "compared with" means. */
    public ReportPeriod previous() {
        return new ReportPeriod("previous", from.minusDays(days()), from.minusDays(1));
    }

    /** How a chart over the period groups its days: by day up to a month, by week up to a quarter, else by month. */
    public Unit unit() {
        return days() <= 31 ? Unit.DAY : days() <= 92 ? Unit.WEEK : Unit.MONTH;
    }

    /** The start of every group in the period, so days without orders show as empty bars. */
    public List<LocalDate> groups() {
        var unit = unit();
        var first = unit.start(from);
        var groups = new ArrayList<LocalDate>();
        for (var group = first; !group.isAfter(to); group = unit.next(group)) {
            groups.add(group);
        }
        return List.copyOf(groups);
    }

    private static Optional<LocalDate> date(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(value.strip()));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** Matches PostgreSQL's date_trunc: weeks start on Monday. */
    public enum Unit {
        DAY,
        WEEK,
        MONTH;

        public LocalDate start(LocalDate date) {
            return switch (this) {
                case DAY -> date;
                case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                case MONTH -> date.withDayOfMonth(1);
            };
        }

        LocalDate next(LocalDate start) {
            return switch (this) {
                case DAY -> start.plusDays(1);
                case WEEK -> start.plusWeeks(1);
                case MONTH -> start.plusMonths(1);
            };
        }

        /** As date_trunc takes it. */
        public String sql() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
