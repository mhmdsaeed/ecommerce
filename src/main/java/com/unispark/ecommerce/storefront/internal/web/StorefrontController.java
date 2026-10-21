package com.unispark.ecommerce.storefront.internal.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.LocaleResolver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unispark.ecommerce.catalog.StorefrontCatalog;
import com.unispark.ecommerce.catalog.StorefrontCatalog.PriceScale;
import com.unispark.ecommerce.catalog.StorefrontCatalog.ProductView;
import com.unispark.ecommerce.catalog.StorefrontCatalog.Sort;
import com.unispark.ecommerce.catalog.StorefrontCatalog.VariantOffer;
import com.unispark.ecommerce.delivery.DeliveryZones;
import com.unispark.ecommerce.inventory.Inventory;
import com.unispark.ecommerce.payment.PaymentMethods;
import com.unispark.ecommerce.pricing.ExchangeRates;
import com.unispark.ecommerce.shared.ListParams;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreSettings;
import com.unispark.ecommerce.tenant.StoreTheme.Look;

/**
 * Storefront browse pages (SPEC 9.2): home, category, product, search and the language switch. The main site's
 * home (no store) is the platform placeholder until marketing pages exist.
 */
@Controller
class StorefrontController {

    /** The main site's landing page, which {@code /} on the main site shows. Store hosts deny it. */
    static final String MAIN_SITE_HOME = "/welcome";
    static final int NEWEST = 8;

    private final StorefrontCatalog catalog;
    private final StoreSettings settings;
    private final ExchangeRates rates;
    private final ShopPrices shopPrices;
    private final LocaleResolver locales;
    private final ObjectMapper json;
    private final Inventory inventory;
    private final SeoTags seo;
    private final DeliveryZones zones;
    private final PaymentMethods methods;

    StorefrontController(StorefrontCatalog catalog, StoreSettings settings, ExchangeRates rates, ShopPrices shopPrices,
            LocaleResolver locales, ObjectMapper json, Inventory inventory, SeoTags seo, DeliveryZones zones,
            PaymentMethods methods) {
        this.inventory = inventory;
        this.seo = seo;
        this.zones = zones;
        this.methods = methods;
        this.catalog = catalog;
        this.settings = settings;
        this.rates = rates;
        this.shopPrices = shopPrices;
        this.locales = locales;
        this.json = json;
    }

    @GetMapping("/")
    String home(Model model) {
        if (TenantContext.currentTenantId().isEmpty()) {
            // The main site: its landing page is the merchant admin module's (LandingController).
            return "forward:" + MAIN_SITE_HOME;
        }
        var newest = catalog.newest(NEWEST);
        var shop = settings.current();
        // A showroom puts its newest product with a photo first, large; the rest follow in the grid.
        var feature = shop.theme().lookOrDefault() == Look.SHOWROOM
                ? newest.stream().filter(card -> card.image() != null).findFirst().orElse(null) : null;
        model.addAttribute("feature", feature);
        model.addAttribute("newest", feature == null ? newest : newest.stream().filter(card -> card != feature).toList());
        model.addAttribute("seo", seo.home(shop));
        return "storefront/home";
    }

    @GetMapping("/c/{slug}")
    String category(@PathVariable String slug, @RequestParam(name = "sort", required = false) String sortParam,
            @RequestParam(name = "page", required = false) String pageParam, Model model) {
        requireStore();
        // Shared and edited links must not end on an error page: unknown values fall back to the defaults.
        var sort = ListParams.constant(Sort.class, sortParam).orElse(Sort.NEWEST);
        var page = ListParams.page(pageParam);
        var category = catalog.category(slug, sort, priceScale(), page)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("page", category);
        model.addAttribute("seo", seo.category(category, settings.current()));
        model.addAttribute("sort", sort);
        model.addAttribute("sorts", Sort.values());
        return "storefront/category";
    }

    @GetMapping("/p/{slug}")
    String product(@PathVariable String slug, Model model) throws JsonProcessingException {
        requireStore();
        var product = catalog.product(slug).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("product", product);
        model.addAttribute("seo", seo.product(product, settings.current()));
        model.addAttribute("variantsJson", variantsJson(product));
        // How it reaches the shopper: where the store delivers and how it can be paid for.
        model.addAttribute("coverage", zones.coverage());
        model.addAttribute("methods", methods.enabled());
        return "storefront/product";
    }

    @GetMapping("/search")
    String search(@RequestParam(defaultValue = "") String q,
            @RequestParam(name = "page", required = false) String pageParam, Model model) {
        requireStore();
        var page = ListParams.page(pageParam);
        var query = q.length() > 100 ? q.substring(0, 100) : q;
        model.addAttribute("q", query);
        model.addAttribute("results", catalog.search(query, page));
        return "storefront/search";
    }

    /** Remembers the language and goes back to the page the shopper was on (same host only). */
    @GetMapping("/lang/{language}")
    String language(@PathVariable String language, HttpServletRequest request, HttpServletResponse response) {
        if (!List.of("ar", "en").contains(language)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        locales.setLocale(request, response, Locale.forLanguageTag(language));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return "redirect:" + backTo(request);
    }

    private static String backTo(HttpServletRequest request) {
        var referer = request.getHeader(HttpHeaders.REFERER);
        if (referer == null) {
            return "/";
        }
        try {
            var uri = URI.create(referer);
            if (!request.getServerName().equalsIgnoreCase(uri.getHost()) || uri.getRawPath() == null
                    || !uri.getRawPath().startsWith("/") || uri.getRawPath().startsWith("/lang/")) {
                return "/";
            }
            return uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (IllegalArgumentException e) {
            return "/";
        }
    }

    /** Sorting by price compares in the display currency, at the current rate when there is one. */
    private PriceScale priceScale() {
        return PriceScale.forDisplay(settings.current().displayCurrency(),
                rates.current().map(ExchangeRates.ExchangeRate::sypPerUsd).orElse(null));
    }

    /** For the Alpine variant picker: each available variant's values, displayed prices and whether it is in stock. */
    private String variantsJson(ProductView product) throws JsonProcessingException {
        var stock = inventory.levels(product.variants().stream().map(VariantOffer::id).toList());
        var variants = product.variants().stream().map(variant -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", variant.id().toString());
            entry.put("values", variant.optionValues());
            entry.put("price", shopPrices.show(variant.price()));
            entry.put("compareAt", shopPrices.show(variant.compareAtPrice()));
            entry.put("inStock", stock.inStock(variant.id()));
            return entry;
        }).toList();
        return json.writeValueAsString(variants);
    }

    private static void requireStore() {
        if (TenantContext.currentTenantId().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
