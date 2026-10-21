package com.unispark.ecommerce.tenant;

import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.unispark.ecommerce.shared.LocalizedText;

/**
 * A storefront's look (SPEC 10: settings only, merchants never edit templates). Stored as the {@code store.theme}
 * JSON. Every field is optional: an empty theme is the platform default, and missing toggles mean "shown".
 *
 * @param primaryColor {@code #rrggbb}; buttons and links derive their shades from it
 * @param logoKey      a media key of the uploaded logo
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StoreTheme(String primaryColor, String logoKey, LocalizedText heroTitle, LocalizedText heroText,
        FontChoice font, Boolean showCategories, Boolean showNewProducts) {

    public static final StoreTheme DEFAULT = new StoreTheme(null, null, null, null, null, null, null);

    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    public StoreTheme {
        if (primaryColor != null && !HEX_COLOR.matcher(primaryColor).matches()) {
            throw new IllegalArgumentException("Not a #rrggbb colour: " + primaryColor);
        }
    }

    public static boolean isColor(String value) {
        return value != null && HEX_COLOR.matcher(value).matches();
    }

    public boolean categoriesShown() {
        return !Boolean.FALSE.equals(showCategories);
    }

    public boolean newProductsShown() {
        return !Boolean.FALSE.equals(showNewProducts);
    }

    public FontChoice fontOrDefault() {
        return font == null ? FontChoice.MODERN : font;
    }

    /**
     * System font stacks only: web fonts would cost download time on slow connections (SPEC 9.5, 18).
     * MODERN is the platform's sans-serif stack; CLASSIC prefers traditional Naskh-style Arabic faces.
     */
    public enum FontChoice {
        MODERN("\"Segoe UI\", Tahoma, \"Noto Sans Arabic\", \"Geeza Pro\", system-ui, -apple-system, Roboto, Arial, "
                + "sans-serif"),
        CLASSIC("\"Traditional Arabic\", \"Noto Naskh Arabic\", \"Times New Roman\", Georgia, serif");

        private final String stack;

        FontChoice(String stack) {
            this.stack = stack;
        }

        /** The CSS font-family value. */
        public String stack() {
            return stack;
        }
    }
}
