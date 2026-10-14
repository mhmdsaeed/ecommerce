package com.unispark.ecommerce.merchantadmin.internal.web.settings;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.unispark.ecommerce.tenant.StoreTheme;
import com.unispark.ecommerce.tenant.StoreTheme.FontChoice;

/** Storefront theme form backing object. The logo has its own upload form. */
public class ThemeForm {

    /** The platform brand colour (brand-600 in app.css): a colour input cannot be empty, so untouched stores keep it. */
    static final String PLATFORM_COLOR = "#00855d";

    @Pattern(regexp = "^$|#[0-9a-fA-F]{6}")
    private String primaryColor;

    @Size(max = 120)
    private String heroTitleAr;

    @Size(max = 120)
    private String heroTitleEn;

    @Size(max = 500)
    private String heroTextAr;

    @Size(max = 500)
    private String heroTextEn;

    @NotNull
    private FontChoice font = FontChoice.MODERN;

    private boolean showCategories = true;

    private boolean showNewProducts = true;

    static ThemeForm of(StoreTheme theme) {
        var form = new ThemeForm();
        form.primaryColor = theme.primaryColor() == null ? PLATFORM_COLOR : theme.primaryColor();
        if (theme.heroTitle() != null) {
            form.heroTitleAr = theme.heroTitle().ar();
            form.heroTitleEn = theme.heroTitle().en();
        }
        if (theme.heroText() != null) {
            form.heroTextAr = theme.heroText().ar();
            form.heroTextEn = theme.heroText().en();
        }
        form.font = theme.fontOrDefault();
        form.showCategories = theme.categoriesShown();
        form.showNewProducts = theme.newProductsShown();
        return form;
    }

    public String getPrimaryColor() {
        return primaryColor;
    }

    public void setPrimaryColor(String primaryColor) {
        this.primaryColor = primaryColor;
    }

    public String getHeroTitleAr() {
        return heroTitleAr;
    }

    public void setHeroTitleAr(String heroTitleAr) {
        this.heroTitleAr = heroTitleAr;
    }

    public String getHeroTitleEn() {
        return heroTitleEn;
    }

    public void setHeroTitleEn(String heroTitleEn) {
        this.heroTitleEn = heroTitleEn;
    }

    public String getHeroTextAr() {
        return heroTextAr;
    }

    public void setHeroTextAr(String heroTextAr) {
        this.heroTextAr = heroTextAr;
    }

    public String getHeroTextEn() {
        return heroTextEn;
    }

    public void setHeroTextEn(String heroTextEn) {
        this.heroTextEn = heroTextEn;
    }

    public FontChoice getFont() {
        return font;
    }

    public void setFont(FontChoice font) {
        this.font = font;
    }

    public boolean isShowCategories() {
        return showCategories;
    }

    public void setShowCategories(boolean showCategories) {
        this.showCategories = showCategories;
    }

    public boolean isShowNewProducts() {
        return showNewProducts;
    }

    public void setShowNewProducts(boolean showNewProducts) {
        this.showNewProducts = showNewProducts;
    }
}
