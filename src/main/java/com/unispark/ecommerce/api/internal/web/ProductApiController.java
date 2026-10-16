package com.unispark.ecommerce.api.internal.web;

import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.unispark.ecommerce.api.internal.web.ApiViews.CategoryResponse;
import com.unispark.ecommerce.api.internal.web.ApiViews.ImageResponse;
import com.unispark.ecommerce.api.internal.web.ApiViews.OptionResponse;
import com.unispark.ecommerce.api.internal.web.ApiViews.PageResponse;
import com.unispark.ecommerce.api.internal.web.ApiViews.PriceResponse;
import com.unispark.ecommerce.api.internal.web.ApiViews.ProductResponse;
import com.unispark.ecommerce.api.internal.web.ApiViews.ProductSummary;
import com.unispark.ecommerce.api.internal.web.ApiViews.VariantResponse;
import com.unispark.ecommerce.catalog.StorefrontCatalog;
import com.unispark.ecommerce.catalog.StorefrontCatalog.PriceScale;
import com.unispark.ecommerce.catalog.StorefrontCatalog.ProductCard;
import com.unispark.ecommerce.catalog.StorefrontCatalog.Sort;
import com.unispark.ecommerce.catalog.StorefrontCatalog.VariantOffer;
import com.unispark.ecommerce.inventory.Inventory;
import com.unispark.ecommerce.media.ImageStore;
import com.unispark.ecommerce.pricing.ExchangeRates;
import com.unispark.ecommerce.pricing.Prices;
import com.unispark.ecommerce.shared.Money;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreSettings;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/** The catalog shoppers see (SPEC 9.2), with the storefront's visibility rules and prices. */
@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Products")
class ProductApiController {

    /** Deep enough for any catalog, small enough that offsets stay far from overflowing. */
    static final int MAX_PAGE = 10_000;
    static final int MAX_QUERY = 100;

    private final StorefrontCatalog catalog;
    private final Prices prices;
    private final ExchangeRates rates;
    private final StoreSettings settings;
    private final Inventory inventory;
    private final ImageStore images;

    ProductApiController(StorefrontCatalog catalog, Prices prices, ExchangeRates rates, StoreSettings settings,
            Inventory inventory, ImageStore images) {
        this.catalog = catalog;
        this.prices = prices;
        this.rates = rates;
        this.settings = settings;
        this.inventory = inventory;
        this.images = images;
    }

    @GetMapping
    @StoreHeader
    @Operation(summary = "Products shoppers can see, 24 per page",
            description = "Newest first by default. With q, a search ranked by relevance (sort does not apply).")
    @ApiResponse(responseCode = "200", description = "A page of products")
    @ApiResponse(responseCode = "400", description = "Missing X-Store-Id, or an invalid parameter")
    @ApiResponse(responseCode = "404", description = "Unknown store or category")
    PageResponse<ProductSummary> products(
            @Parameter(description = "A category's slug; includes its subcategories")
            @RequestParam(required = false) String category,
            @Parameter(description = "Search text (Arabic is matched without diacritics or hamza forms)")
            @RequestParam(required = false) String q,
            @Parameter(schema = @Schema(allowableValues = {"newest", "price_asc", "price_desc"}))
            @RequestParam(defaultValue = "newest") String sort,
            @Parameter(description = "Zero-based") @RequestParam(defaultValue = "0") int page) {
        requireStore();
        if (page < 0 || page > MAX_PAGE) {
            throw badRequest("page must be between 0 and " + MAX_PAGE + ".");
        }
        var order = sort(sort);
        if (q != null) {
            if (category != null) {
                throw badRequest("Use either q or category.");
            }
            if (q.length() > MAX_QUERY) {
                throw badRequest("q is limited to " + MAX_QUERY + " characters.");
            }
            return PageResponse.of(catalog.search(q, page), this::summary);
        }
        var scale = PriceScale.forDisplay(settings.current().displayCurrency(),
                rates.current().map(ExchangeRates.ExchangeRate::sypPerUsd).orElse(null));
        if (category != null) {
            var found = catalog.category(category, order, scale, page)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such category."));
            return PageResponse.of(found.results(), this::summary);
        }
        return PageResponse.of(catalog.products(order, scale, page), this::summary);
    }

    @GetMapping("/{id}")
    @StoreHeader
    @Operation(summary = "A product with its available variants")
    @ApiResponse(responseCode = "200", description = "The product")
    @ApiResponse(responseCode = "400", description = "Missing X-Store-Id")
    @ApiResponse(responseCode = "404", description = "Unknown store, or no such product for shoppers")
    ProductResponse product(@PathVariable UUID id) {
        requireStore();
        var product = catalog.product(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such product."));
        var stock = inventory.levels(product.variants().stream().map(VariantOffer::id).toList());
        return new ProductResponse(product.id(), product.slug(), product.name(), product.description(),
                CategoryResponse.of(product.category()),
                product.options().stream()
                        .map(option -> new OptionResponse(option.position(), option.name(), option.values())).toList(),
                product.variants().stream()
                        .map(variant -> new VariantResponse(variant.id(), variant.optionValues(),
                                price(variant.price()), price(variant.compareAtPrice()), stock.inStock(variant.id())))
                        .toList(),
                product.images().stream().map(image -> ImageResponse.of(image, images)).toList());
    }

    private ProductSummary summary(ProductCard card) {
        return new ProductSummary(card.id(), card.slug(), card.name(), price(card.price()),
                price(card.compareAtPrice()), card.fromPrice(), ImageResponse.of(card.image(), images));
    }

    private PriceResponse price(Money money) {
        return money == null ? null : PriceResponse.of(prices.display(money).amount());
    }

    private static Sort sort(String value) {
        try {
            return Sort.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw badRequest("sort must be newest, price_asc or price_desc.");
        }
    }

    private static void requireStore() {
        if (TenantContext.currentTenantId().isEmpty()) {
            throw badRequest("The X-Store-Id header is required.");
        }
    }

    private static ResponseStatusException badRequest(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }
}
