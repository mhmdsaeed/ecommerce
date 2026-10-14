package com.unispark.ecommerce.catalog.internal;

import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * URL slugs for categories and products ({@code /c/<slug>}, {@code /p/<slug>}). Arabic letters are allowed, so an
 * Arabic product name keeps a readable Arabic URL; browsers show it decoded.
 *
 * <p>A slug is lowercase letters and digits of any script, in groups joined by single hyphens, at most
 * {@value #MAX_LENGTH} characters. Generated slugs drop diacritics (including Arabic tashkeel) and tatweel.
 */
public final class Slugs {

    public static final int MAX_LENGTH = 80;

    private static final Pattern VALID = Pattern.compile("[\\p{L}\\p{Nd}]+(-[\\p{L}\\p{Nd}]+)*");
    private static final Pattern MARKS = Pattern.compile("[\\p{Mn}\\u0640]");
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{Nd}]+");

    private Slugs() {
    }

    public static String normalize(String input) {
        return input == null ? "" : input.strip().toLowerCase(Locale.ROOT);
    }

    public static boolean isValid(String slug) {
        return slug.length() <= MAX_LENGTH && slug.equals(slug.toLowerCase(Locale.ROOT)) && VALID.matcher(slug).matches();
    }

    /** A valid slug from a name, or {@code fallback} when the name has no letters or digits. */
    public static String fromName(String name, String fallback) {
        var decomposed = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFKD);
        var slug = SEPARATORS.matcher(MARKS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT))
                .replaceAll("-");
        slug = trimHyphens(slug);
        if (slug.length() > MAX_LENGTH) {
            slug = trimHyphens(slug.substring(0, MAX_LENGTH));
        }
        slug = Normalizer.normalize(slug, Normalizer.Form.NFC);
        return slug.isEmpty() ? fallback : slug;
    }

    /** {@code base}, or {@code base-2}, {@code base-3}… until {@code taken} says no. */
    public static String unique(String base, Predicate<String> taken) {
        if (!taken.test(base)) {
            return base;
        }
        for (int suffix = 2; ; suffix++) {
            var tail = "-" + suffix;
            var stem = base.length() + tail.length() > MAX_LENGTH ? base.substring(0, MAX_LENGTH - tail.length()) : base;
            var candidate = trimHyphens(stem) + tail;
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
    }

    private static String trimHyphens(String value) {
        var start = 0;
        var end = value.length();
        while (start < end && value.charAt(start) == '-') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '-') {
            end--;
        }
        return value.substring(start, end);
    }
}
