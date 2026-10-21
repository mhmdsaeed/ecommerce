package com.unispark.ecommerce.tenant;

import java.awt.Color;
import java.util.Locale;
import java.util.Optional;

/**
 * The shades a store's colour gives its storefront, as {@code #rrggbb}. The one place they are worked out: the
 * storefront's style and the theme settings preview both use it.
 *
 * <p>Buttons put white text on the 600 shade, so it is darkened until white text reaches WCAG AA contrast
 * (4.5:1); a merchant choosing a light brand colour still gets readable buttons. 50 and 100 are the colour mixed
 * with white, 500 the colour itself, 700 the 600 a fifth darker.
 */
public record ThemePalette(String shade50, String shade100, String shade500, String shade600, String shade700) {

    public static final double MIN_CONTRAST_WITH_WHITE = 4.5;

    /** Empty unless the value is a {@code #rrggbb} colour. */
    public static Optional<ThemePalette> of(String color) {
        if (!StoreTheme.isColor(color)) {
            return Optional.empty();
        }
        var base = Color.decode(color);
        var button = readableOnWhite(base);
        return Optional.of(new ThemePalette(hex(mix(base, Color.WHITE, 0.92)), hex(mix(base, Color.WHITE, 0.82)),
                hex(base), hex(button), hex(mix(button, Color.BLACK, 0.2))));
    }

    /** The colour, darkened in small steps until white text on it is readable. */
    static Color readableOnWhite(Color color) {
        var result = color;
        for (int step = 0; step < 40 && contrastWithWhite(result) < MIN_CONTRAST_WITH_WHITE; step++) {
            result = mix(result, Color.BLACK, 0.06);
        }
        return result;
    }

    public static double contrastWithWhite(String color) {
        return contrastWithWhite(Color.decode(color));
    }

    static double contrastWithWhite(Color color) {
        return 1.05 / (luminance(color) + 0.05);
    }

    private static double luminance(Color color) {
        return 0.2126 * linear(color.getRed()) + 0.7152 * linear(color.getGreen()) + 0.0722 * linear(color.getBlue());
    }

    private static double linear(int channel) {
        var value = channel / 255.0;
        return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static Color mix(Color color, Color with, double amount) {
        return new Color(
                (int) Math.round(color.getRed() + (with.getRed() - color.getRed()) * amount),
                (int) Math.round(color.getGreen() + (with.getGreen() - color.getGreen()) * amount),
                (int) Math.round(color.getBlue() + (with.getBlue() - color.getBlue()) * amount));
    }

    private static String hex(Color color) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }
}
