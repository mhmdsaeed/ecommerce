package com.unispark.ecommerce.storefront.internal.web;

import java.util.Comparator;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import com.unispark.ecommerce.catalog.StorefrontCatalog.CategoryPage;
import com.unispark.ecommerce.catalog.StorefrontCatalog.ProductView;
import com.unispark.ecommerce.catalog.StorefrontCatalog.VariantOffer;
import com.unispark.ecommerce.tenant.StoreSettings.Settings;

/**
 * The description and Open Graph tags of indexable storefront pages (SPEC 9.5), in the visitor's language: what
 * search results and link previews in WhatsApp or Facebook show. Taken from the merchant's own texts where there
 * are some, otherwise from the bundles.
 */
@Component
class SeoTags {

    /** Search engines show about this much of a description. */
    static final int MAX_DESCRIPTION = 160;

    private final MessageSource messages;
    private final ShopImages images;
    private final ShopPrices prices;

    SeoTags(MessageSource messages, ShopImages images, ShopPrices prices) {
        this.messages = messages;
        this.images = images;
        this.prices = prices;
    }

    Seo home(Settings shop) {
        var locale = LocaleContextHolder.getLocale();
        var name = shop.name().in(locale);
        var hero = shop.theme().heroText();
        var description = hero != null ? hero.in(locale)
                : messages.getMessage("shop.seo.home", new Object[] {name}, locale);
        var logo = shop.theme().logoKey() == null ? null : images.large(shop.theme().logoKey());
        return new Seo(name, summary(description), logo, "website");
    }

    Seo category(CategoryPage page, Settings shop) {
        var locale = LocaleContextHolder.getLocale();
        var name = page.category().name().in(locale);
        var description = messages.getMessage("shop.seo.category",
                new Object[] {name, shop.name().in(locale)}, locale);
        var first = page.results().products().stream().findFirst()
                .filter(card -> card.image() != null).map(card -> images.large(card.image().storageKey()));
        return new Seo(name, summary(description), first.orElse(null), "website");
    }

    Seo product(ProductView product, Settings shop) {
        var locale = LocaleContextHolder.getLocale();
        var name = product.name().in(locale);
        var own = product.description() == null ? null : product.description().in(locale);
        var description = own != null && !own.isBlank() ? own : messages.getMessage("shop.seo.product",
                new Object[] {name, lowestPrice(product), shop.name().in(locale)}, locale);
        var image = product.images().isEmpty() ? null : images.large(product.images().getFirst().storageKey());
        return new Seo(name, summary(description), image, "product");
    }

    private String lowestPrice(ProductView product) {
        return product.variants().stream().map(VariantOffer::price)
                .min(Comparator.comparing(price -> price.amount())).map(prices::show).orElse("");
    }

    /** One line of plain text, cut at a word within {@link #MAX_DESCRIPTION} characters. */
    static String summary(String text) {
        if (text == null) {
            return null;
        }
        var line = text.strip().replaceAll("\\s+", " ");
        if (line.length() <= MAX_DESCRIPTION) {
            return line;
        }
        var cut = line.lastIndexOf(' ', MAX_DESCRIPTION - 1);
        return line.substring(0, cut > MAX_DESCRIPTION / 2 ? cut : MAX_DESCRIPTION - 1) + "…";
    }

    /**
     * @param image an absolute image URL, or null
     * @param type  the Open Graph type: website or product
     */
    record Seo(String title, String description, String image, String type) {
    }
}
