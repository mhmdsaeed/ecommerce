package com.unispark.ecommerce.storefront.internal.web;

import java.awt.Color;
import java.util.Locale;

import com.unispark.ecommerce.tenant.StoreTheme;
import com.unispark.ecommerce.tenant.StoreTheme.FontChoice;

/**
 * Turns a store theme into CSS custom properties for the page, overriding Tailwind's brand palette and font stack.
 * Only values built here from a validated {@code #rrggbb} colour and an enum go into the style attribute.
 *
 * <p>Buttons put white text on the 600 shade, so it is darkened until white text reaches WCAG AA contrast
 * (4.5:1); a merchant choosing a light brand colour still gets readable buttons.
 */
final class ThemeStyle {

    static final double MIN_CONTRAST_WITH_WHITE = 4.5;

    static final String MODERN_FONTS = "\"Segoe UI\", Tahoma, \"Noto Sans Arabic\", \"Geeza Pro\", system-ui, "
            + "-apple-system, Roboto, Arial, sans-serif";
    static final String CLASSIC_FONTS = "\"Traditional Arabic\", \"Noto Naskh Arabic\", \"Times New Roman\", "
            + "Georgia, serif";

    private ThemeStyle() {
    }

    static String css(StoreTheme theme) {
        var style = new StringBuilder();
        if (StoreTheme.isColor(theme.primaryColor())) {
            var base = Color.decode(theme.primaryColor());
            var button = readableOnWhite(base);
            style.append("--color-brand-50:").append(hex(mix(base, Color.WHITE, 0.92))).append(';')
                    .append("--color-brand-100:").append(hex(mix(base, Color.WHITE, 0.82))).append(';')
                    .append("--color-brand-500:").append(hex(base)).append(';')
                    .append("--color-brand-600:").append(hex(button)).append(';')
                    .append("--color-brand-700:").append(hex(mix(button, Color.BLACK, 0.2))).append(';');
        }
        if (theme.fontOrDefault() == FontChoice.CLASSIC) {
            style.append("--font-sans:").append(CLASSIC_FONTS).append(';');
        }
        return style.isEmpty() ? null : style.toString();
    }

    /** The colour, darkened in small steps until white text on it is readable. */
    static Color readableOnWhite(Color color) {
        var result = color;
        for (int step = 0; step < 40 && contrastWithWhite(result) < MIN_CONTRAST_WITH_WHITE; step++) {
            result = mix(result, Color.BLACK, 0.06);
        }
        return result;
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

    static String hex(Color color) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }
}
