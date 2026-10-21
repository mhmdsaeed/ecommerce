package com.unispark.ecommerce.storefront.internal.web;

import com.unispark.ecommerce.tenant.StoreTheme;
import com.unispark.ecommerce.tenant.StoreTheme.FontChoice;
import com.unispark.ecommerce.tenant.ThemePalette;

/**
 * Turns a store theme into CSS custom properties for the page, overriding Tailwind's brand palette and font stack.
 * Only values built here from a validated {@code #rrggbb} colour ({@link ThemePalette}) and an enum go into the
 * style attribute.
 */
final class ThemeStyle {

    private ThemeStyle() {
    }

    static String css(StoreTheme theme) {
        var style = new StringBuilder();
        ThemePalette.of(theme.primaryColor()).ifPresent(palette -> style
                .append("--color-brand-50:").append(palette.shade50()).append(';')
                .append("--color-brand-100:").append(palette.shade100()).append(';')
                .append("--color-brand-500:").append(palette.shade500()).append(';')
                .append("--color-brand-600:").append(palette.shade600()).append(';')
                .append("--color-brand-700:").append(palette.shade700()).append(';'));
        if (theme.fontOrDefault() == FontChoice.CLASSIC) {
            style.append("--font-sans:").append(FontChoice.CLASSIC.stack()).append(';');
        }
        return style.isEmpty() ? null : style.toString();
    }
}
