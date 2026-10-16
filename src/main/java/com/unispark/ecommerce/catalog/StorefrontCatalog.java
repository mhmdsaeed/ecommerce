package com.unispark.ecommerce.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.unispark.ecommerce.catalog.Products.ImageView;
import com.unispark.ecommerce.catalog.Products.OptionView;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.Money;

/**
 * What shoppers of the current store may see (SPEC 9.2). A product is visible when it is published, has an
 * available variant, and is not in a hidden category (or a category under a hidden one). Hidden categories have
 * no page. Prices come in the product's own currency; the storefront converts them for display.
 */
public interface StorefrontCatalog {

    int PAGE_SIZE = 24;

    /** Visible top-level categories with their visible subcategories, in sort order. */
    List<CategoryLink> navigation();

    List<ProductCard> newest(int limit);

    Optional<CategoryPage> category(String slug, Sort sort, PriceScale scale, int page);

    /** Every visible product, a page at a time. */
    ResultPage products(Sort sort, PriceScale scale, int page);

    Optional<ProductView> product(String slug);

    Optional<ProductView> product(UUID id);

    ResultPage search(String query, int page);

    /**
     * Every page shoppers can reach, for the store's sitemap: visible categories, and visible products newest
     * change first, at most {@code maxProducts} of them.
     */
    SitemapEntries sitemap(int maxProducts);

    /**
     * The variants among {@code variantIds} that shoppers may buy now: available variants of visible products.
     * Missing ids are not for sale (archived product, hidden category, unknown id).
     */
    Map<UUID, Offer> offers(Collection<UUID> variantIds);

    enum Sort {
        NEWEST,
        PRICE_ASC,
        PRICE_DESC
    }

    /**
     * How to compare prices in different currencies when sorting: prices in {@code foreign} are multiplied by
     * {@code factor} (the rate, or its inverse). {@link #NONE} compares raw amounts, when there is no rate.
     */
    record PriceScale(Currency foreign, BigDecimal factor) {

        public static final PriceScale NONE = new PriceScale(null, BigDecimal.ONE);

        /**
         * Compares prices in the {@code display} currency: the other currency is scaled at {@code sypPerUsd}, or
         * {@link #NONE} without a rate.
         */
        public static PriceScale forDisplay(Currency display, BigDecimal sypPerUsd) {
            if (sypPerUsd == null) {
                return NONE;
            }
            return display == Currency.SYP
                    ? new PriceScale(Currency.USD, sypPerUsd)
                    : new PriceScale(Currency.SYP, BigDecimal.ONE.divide(sypPerUsd, 10, RoundingMode.HALF_UP));
        }
    }

    record CategoryLink(UUID id, LocalizedText name, String slug, List<CategoryLink> children) {
    }

    /** {@code price} is the lowest available variant's; {@code fromPrice} when variants differ in price. */
    record ProductCard(UUID id, LocalizedText name, String slug, Money price, Money compareAtPrice, boolean fromPrice,
            ImageView image) {
    }

    record ResultPage(List<ProductCard> products, int page, int totalPages, long totalItems) {

        public boolean hasPrevious() {
            return page > 0;
        }

        public boolean hasNext() {
            return page + 1 < totalPages;
        }
    }

    record CategoryPage(CategoryLink category, CategoryLink parent, ResultPage results) {
    }

    record VariantOffer(UUID id, List<String> optionValues, Money price, Money compareAtPrice) {
    }

    /** Only available variants; {@code options} list only values that some available variant has. */
    record ProductView(UUID id, LocalizedText name, LocalizedText description, String slug, CategoryLink category,
            List<OptionView> options, List<VariantOffer> variants, List<ImageView> images) {
    }

    /**
     * A purchasable variant as a cart line shows it and an order snapshots it. The image is the variant's own photo
     * if it has one; the SKU may be null.
     */
    record Offer(UUID variantId, UUID productId, LocalizedText productName, String productSlug, List<Choice> choices,
            Money price, ImageView image, String sku) {
    }

    /** Slugs, with when each product last changed. */
    record SitemapEntries(List<String> categories, List<SitemapProduct> products) {
    }

    record SitemapProduct(String slug, Instant updatedAt) {
    }

    /** One option value of a variant, such as Size M. */
    record Choice(LocalizedText option, LocalizedText value) {
    }
}
