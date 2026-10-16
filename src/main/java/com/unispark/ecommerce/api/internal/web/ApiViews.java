package com.unispark.ecommerce.api.internal.web;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.unispark.ecommerce.catalog.Products.ImageView;
import com.unispark.ecommerce.catalog.StorefrontCatalog.CategoryLink;
import com.unispark.ecommerce.catalog.StorefrontCatalog.ProductCard;
import com.unispark.ecommerce.catalog.StorefrontCatalog.ResultPage;
import com.unispark.ecommerce.media.ImageSize;
import com.unispark.ecommerce.media.ImageStore;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.Money;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The API's JSON shapes (v1). Texts come in both languages, so the app picks its own. Prices are what shoppers pay:
 * in the store's display currency, converted and rounded as on the storefront. Amounts are strings, so no client
 * parses them as binary floating point.
 */
final class ApiViews {

    private ApiViews() {
    }

    @Schema(name = "Store")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record StoreResponse(UUID id, String slug, LocalizedText name,
            @Schema(description = "Language for users who have not chosen one", example = "ar") String defaultLanguage,
            Currency displayCurrency, @Schema(description = "#rrggbb") String primaryColor, String logoUrl,
            @Schema(description = "E.164") String contactPhone, @Schema(description = "E.164") String whatsapp,
            @Schema(description = "The storefront's address") String url) {
    }

    @Schema(name = "Price")
    record PriceResponse(@Schema(description = "Always two decimals", example = "15000.00") String amount,
            Currency currency) {

        static PriceResponse of(Money money) {
            return money == null ? null : new PriceResponse(money.amount().toPlainString(), money.currency());
        }
    }

    @Schema(name = "Image", description = "WebP renditions 320, 640 and 1024 pixels wide (never upscaled)")
    record ImageResponse(int width, int height, String small, String medium, String large) {

        static ImageResponse of(ImageView image, ImageStore images) {
            return image == null ? null : new ImageResponse(image.width(), image.height(),
                    images.url(image.storageKey(), ImageSize.SMALL), images.url(image.storageKey(), ImageSize.MEDIUM),
                    images.url(image.storageKey(), ImageSize.LARGE));
        }
    }

    @Schema(name = "CategoryRef")
    record CategoryResponse(UUID id, String slug, LocalizedText name) {

        static CategoryResponse of(CategoryLink link) {
            return link == null ? null : new CategoryResponse(link.id(), link.slug(), link.name());
        }
    }

    @Schema(name = "ProductSummary")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ProductSummary(UUID id, String slug, LocalizedText name,
            @Schema(description = "The lowest available variant's price") PriceResponse price,
            PriceResponse compareAtPrice,
            @Schema(description = "Variants differ in price; show \"from\"") boolean fromPrice,
            ImageResponse image) {
    }

    @Schema(name = "Product")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ProductResponse(UUID id, String slug, LocalizedText name, LocalizedText description,
            CategoryResponse category, List<OptionResponse> options, List<VariantResponse> variants,
            List<ImageResponse> images) {
    }

    @Schema(name = "Option", description = "Only values that some available variant has")
    record OptionResponse(int position, LocalizedText name, List<LocalizedText> values) {
    }

    @Schema(name = "Variant")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record VariantResponse(UUID id,
            @Schema(description = "One value label per option, in option order") List<String> optionValues,
            PriceResponse price, PriceResponse compareAtPrice, boolean inStock) {
    }

    @Schema(name = "ProductPage")
    record PageResponse<T>(List<T> items, @Schema(description = "Zero-based") int page, int totalPages,
            long totalItems) {

        static <T> PageResponse<T> of(ResultPage results, Function<ProductCard, T> mapper) {
            return new PageResponse<>(results.products().stream().map(mapper).toList(), results.page(),
                    results.totalPages(), results.totalItems());
        }
    }
}
