package com.unispark.ecommerce.merchantadmin.internal.web.catalog;

import java.util.Comparator;

import com.unispark.ecommerce.catalog.Products.ImageView;
import com.unispark.ecommerce.catalog.Products.ProductDetails;
import com.unispark.ecommerce.catalog.Products.VariantView;
import com.unispark.ecommerce.shared.Money;

/**
 * The product as the shop shows it, for the top of its edit page: its card (main photo, the lowest price of what is
 * on sale, the old price if that one has one) and what publishing it needs.
 *
 * @param imageKey       the main photo, or null
 * @param price          the lowest price above zero among variants on sale, or null: then it cannot be published
 * @param fromPrice      variants on sale have different prices, so the card says "from"
 * @param compareAtPrice the old price of the cheapest variant, when above its price; null otherwise
 */
record ShelfPreview(String imageKey, Money price, boolean fromPrice, Money compareAtPrice, boolean hasDescription) {

    static ShelfPreview of(ProductDetails product) {
        var onSale = product.variants().stream()
                .filter(variant -> variant.active() && variant.price() != null && variant.price().isPositive())
                .toList();
        var cheapest = onSale.stream().min(Comparator.comparing(variant -> variant.price().amount()));
        var price = cheapest.map(VariantView::price).orElse(null);
        var compareAt = cheapest.map(VariantView::compareAtPrice).filter(was -> price.isLessThan(was)).orElse(null);
        var fromPrice = onSale.stream().map(variant -> variant.price().amount()).distinct().count() > 1;
        var imageKey = product.images().stream().findFirst().map(ImageView::storageKey).orElse(null);
        return new ShelfPreview(imageKey, price, fromPrice, compareAt, product.description() != null);
    }

    public boolean hasPhoto() {
        return imageKey != null;
    }

    /** What publishing needs: a variant on sale at a price above zero (as {@code Products.changeStatus} checks). */
    public boolean sellable() {
        return price != null;
    }
}
