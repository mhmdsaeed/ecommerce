package com.unispark.ecommerce.merchantadmin.internal.web.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.unispark.ecommerce.catalog.ProductStatus;
import com.unispark.ecommerce.catalog.Products.ImageView;
import com.unispark.ecommerce.catalog.Products.ProductDetails;
import com.unispark.ecommerce.catalog.Products.VariantView;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.Money;

class ShelfPreviewTest {

    @Test
    void showsTheLowestPriceOnSaleWithItsOldPrice() {
        var shelf = ShelfPreview.of(product(null, List.of(),
                variant("175000", null, true), variant("150000", "200000", true), variant("90000", null, false)));

        assertThat(shelf.price()).isEqualTo(syp("150000"));
        assertThat(shelf.compareAtPrice()).isEqualTo(syp("200000"));
        assertThat(shelf.fromPrice()).isTrue();
        assertThat(shelf.sellable()).isTrue();
    }

    @Test
    void saysFromOnlyWhenPricesDiffer() {
        var shelf = ShelfPreview.of(product(null, List.of(), variant("1000", "900", true), variant("1000", null, true)));

        assertThat(shelf.fromPrice()).isFalse();
        assertThat(shelf.compareAtPrice()).as("an old price below the price is not shown").isNull();
    }

    @Test
    void isNotSellableWithoutAPriceAboveZeroOnSale() {
        var shelf = ShelfPreview.of(product(null, List.of(), variant("0", null, true), variant("5000", null, false)));

        assertThat(shelf.sellable()).isFalse();
        assertThat(shelf.price()).isNull();
        assertThat(shelf.compareAtPrice()).isNull();
    }

    @Test
    void knowsWhetherThereIsAPhotoAndADescription() {
        var bare = ShelfPreview.of(product(null, List.of(), variant("1000", null, true)));
        var complete = ShelfPreview.of(product(LocalizedText.of("قطن", null),
                List.of(new ImageView(UUID.randomUUID(), "products/a.jpg", 800, 800, 0),
                        new ImageView(UUID.randomUUID(), "products/b.jpg", 800, 800, 1)),
                variant("1000", null, true)));

        assertThat(bare.hasPhoto()).isFalse();
        assertThat(bare.hasDescription()).isFalse();
        assertThat(complete.imageKey()).isEqualTo("products/a.jpg");
        assertThat(complete.hasDescription()).isTrue();
    }

    private static ProductDetails product(LocalizedText description, List<ImageView> images, VariantView... variants) {
        return new ProductDetails(UUID.randomUUID(), LocalizedText.of("قميص", null), description, "shirt", null,
                ProductStatus.DRAFT, Currency.SYP, List.of(), List.of(variants), images);
    }

    private static VariantView variant(String price, String compareAt, boolean active) {
        return new VariantView(UUID.randomUUID(), List.of(), null, syp(price), compareAt == null ? null : syp(compareAt),
                active);
    }

    private static Money syp(String amount) {
        return Money.of(new BigDecimal(amount), Currency.SYP);
    }
}
