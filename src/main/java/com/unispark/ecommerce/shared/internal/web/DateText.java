package com.unispark.ecommerce.shared.internal.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Dates in templates, used as the bean named dates: in Asia/Damascus (SPEC 11), in the request locale, so digits
 * follow the store's setting.
 */
@Component("dates")
class DateText {

    static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Damascus");

    /** Date and time, such as 14 Oct 2026, 21:05. */
    public String format(Instant instant) {
        if (instant == null) {
            return "";
        }
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(LocaleContextHolder.getLocale()).withZone(DISPLAY_ZONE).format(instant);
    }

    /** A calendar day, such as 14 Oct 2026. */
    public String day(LocalDate date) {
        if (date == null) {
            return "";
        }
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(LocaleContextHolder.getLocale())
                .format(date);
    }
}
