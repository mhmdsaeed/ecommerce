package com.unispark.ecommerce.catalog;

import java.math.BigDecimal;
import java.util.List;
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

    Optional<ProductView> product(String slug);

    ResultPage search(String query, int page);

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
}
