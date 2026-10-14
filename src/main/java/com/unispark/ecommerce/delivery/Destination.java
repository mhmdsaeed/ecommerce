package com.unispark.ecommerce.delivery;

import java.util.Objects;
import java.util.regex.Pattern;

import com.unispark.ecommerce.shared.ArabicText;

/**
 * Where an order goes, in the terms zones match on: a governorate, and optionally a city and an area of that city.
 * Names are stripped and their inner spaces collapsed; blank means absent, and an area needs a city.
 */
public record Destination(Governorate governorate, String city, String area) {

    private static final Pattern SPACES = Pattern.compile("\s+");

    public Destination {
        Objects.requireNonNull(governorate, "governorate");
        city = clean(city);
        area = city == null ? null : clean(area);
    }

    public static Destination of(Governorate governorate, String city, String area) {
        return new Destination(governorate, city, area);
    }

    /** The city as matched against zones: different spellings of one name give the same key. Null without a city. */
    public String cityKey() {
        return key(city);
    }

    public String areaKey() {
        return key(area);
    }

    /** The trimmed name with single spaces, or null when blank. */
    public static String clean(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return SPACES.matcher(name.strip()).replaceAll(" ");
    }

    /** The matching key of a place name; null for a blank name, or one made only of marks such as tatweel. */
    public static String key(String name) {
        var cleaned = clean(name);
        var folded = cleaned == null ? "" : ArabicText.fold(cleaned);
        return folded.isEmpty() ? null : folded;
    }
}
