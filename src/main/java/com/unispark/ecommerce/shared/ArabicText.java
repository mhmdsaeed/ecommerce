package com.unispark.ecommerce.shared;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Folds the spelling variants Arabic writers use interchangeably, for matching what people type: lower case,
 * diacritics and tatweel removed, hamza forms of alef to alef, alef maksura to ya, ta marbuta to ha, runs of
 * whitespace to one space. So احمر matches أحمر, مدرسه matches مدرسة and "المزة " matches "المزه".
 */
public final class ArabicText {

    private static final Pattern MARKS = Pattern.compile("[\\u064B-\\u065F\\u0670\\u0640]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private ArabicText() {
    }

    /** The folded form; "" for null. */
    public static String fold(String text) {
        if (text == null) {
            return "";
        }
        var stripped = MARKS.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
        var result = new StringBuilder(stripped.length());
        for (var ch : stripped.toCharArray()) {
            result.append(switch (ch) {
                case 'أ', 'إ', 'آ', 'ٱ' -> 'ا';
                case 'ى' -> 'ي';
                case 'ة' -> 'ه';
                default -> ch;
            });
        }
        return SPACES.matcher(result.toString().strip()).replaceAll(" ");
    }
}
