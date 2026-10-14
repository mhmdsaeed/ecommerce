package com.unispark.ecommerce.catalog.internal;

import java.util.Comparator;

import com.unispark.ecommerce.catalog.Products.ImageView;
import com.unispark.ecommerce.catalog.Products.OptionView;
import com.unispark.ecommerce.catalog.Products.ProductDetails;
import com.unispark.ecommerce.catalog.Products.ProductSummary;
import com.unispark.ecommerce.catalog.Products.VariantView;
import com.unispark.ecommerce.catalog.internal.persistence.Product;
import com.unispark.ecommerce.catalog.internal.persistence.ProductImage;
import com.unispark.ecommerce.catalog.internal.persistence.ProductVariant;
import com.unispark.ecommerce.shared.Money;

/** Entity to API record mapping. Call inside a transaction: it reads the product's collections. */
final class CatalogViews {

    private CatalogViews() {
    }

    static ProductSummary summary(Product product) {
        var variants = product.getVariants();
        var lowest = variants.stream().filter(ProductVariant::isActive).map(ProductVariant::getPrice)
                .min(Comparator.naturalOrder())
                .or(() -> variants.stream().map(ProductVariant::getPrice).min(Comparator.naturalOrder()))
                .map(price -> Money.of(price, product.getPriceCurrency()))
                .orElse(null);
        var imageKey = product.getImages().stream().findFirst().map(ProductImage::getStorageKey).orElse(null);
        return new ProductSummary(product.getId(), product.getName(), product.getSlug(), product.getStatus(), lowest,
                variants.size(), imageKey, product.getUpdatedAt());
    }

    static ProductDetails details(Product product) {
        return new ProductDetails(product.getId(), product.getName(), product.getDescription(), product.getSlug(),
                product.getCategoryId(), product.getStatus(), product.getPriceCurrency(),
                product.getOptions().stream()
                        .map(option -> new OptionView(option.getPosition(), option.getName(), option.getChoices()))
                        .toList(),
                product.getVariants().stream()
                        .map(variant -> new VariantView(variant.getId(), variant.getOptionValues(), variant.getSku(),
                                variant.price(), variant.compareAtPrice(), variant.isActive()))
                        .toList(),
                product.getImages().stream().map(CatalogViews::image).toList());
    }

    static ImageView image(ProductImage image) {
        return new ImageView(image.getId(), image.getStorageKey(), image.getWidth(), image.getHeight(),
                image.getSortOrder());
    }
}
