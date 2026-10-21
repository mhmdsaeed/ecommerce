package com.unispark.ecommerce.storefront.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.unispark.ecommerce.tenant.StoreTheme;
import com.unispark.ecommerce.tenant.StoreTheme.FontChoice;

class ThemeStyleTest {

    @Test
    void addsNothingForTheDefaultTheme() {
        assertThat(ThemeStyle.css(StoreTheme.DEFAULT)).isNull();
    }

    @Test
    void derivesTheBrandShadesFromTheChosenColour() {
        var css = ThemeStyle.css(new StoreTheme("#1e88e5", null, null, null, null, null, null));

        assertThat(css).contains("--color-brand-500:#1e88e5;").contains("--color-brand-50:")
                .contains("--color-brand-600:").contains("--color-brand-700:").doesNotContain("--font-sans");
    }

    @Test
    void switchesToTheClassicFontStack() {
        assertThat(ThemeStyle.css(new StoreTheme(null, null, null, null, FontChoice.CLASSIC, null, null)))
                .startsWith("--font-sans:\"Traditional Arabic\"");
    }
}
