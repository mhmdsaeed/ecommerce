package com.unispark.ecommerce.storefront.internal.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import com.unispark.ecommerce.pricing.ExchangeRates;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreSettings;

/**
 * Storefront browse pages (SPEC 9.2): home, category, product, search and the language switch. The main site's
 * home (no store) is the platform placeholder until marketing pages exist.
 */
@Controller
class StorefrontController {

    static final int NEWEST = 8;

    private final StorefrontCatalog catalog;
    private final StoreSettings settings;
    private final ExchangeRates rates;
    private final ShopPrices shopPrices;
    private final LocaleResolver locales;
    private final ObjectMapper json;

    StorefrontController(StorefrontCatalog catalog, StoreSettings settings, ExchangeRates rates, ShopPrices shopPrices,
            LocaleResolver locales, ObjectMapper json) {
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
            return "storefront/placeholder";
        }
        model.addAttribute("newest", catalog.newest(NEWEST));
        return "storefront/home";
    }

    @GetMapping("/c/{slug}")
    String category(@PathVariable String slug, @RequestParam(defaultValue = "NEWEST") Sort sort,
            @RequestParam(defaultValue = "0") int page, Model model) {
        requireStore();
        var category = catalog.category(slug, sort, priceScale(), page)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("page", category);
        model.addAttribute("sort", sort);
        model.addAttribute("sorts", Sort.values());
        return "storefront/category";
    }

    @GetMapping("/p/{slug}")
    String product(@PathVariable String slug, Model model) throws JsonProcessingException {
        requireStore();
        var product = catalog.product(slug).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("product", product);
        model.addAttribute("variantsJson", variantsJson(product));
        return "storefront/product";
    }

    @GetMapping("/search")
    String search(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page, Model model) {
        requireStore();
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
        var display = settings.current().displayCurrency();
        return rates.current().map(rate -> display == Currency.SYP
                        ? new PriceScale(Currency.USD, rate.sypPerUsd())
                        : new PriceScale(Currency.SYP, BigDecimal.ONE.divide(rate.sypPerUsd(), 10, RoundingMode.HALF_UP)))
                .orElse(PriceScale.NONE);
    }

    /** For the Alpine variant picker: each available variant's values and its displayed prices. */
    private String variantsJson(ProductView product) throws JsonProcessingException {
        var variants = product.variants().stream().map(variant -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", variant.id().toString());
            entry.put("values", variant.optionValues());
            entry.put("price", shopPrices.show(variant.price()));
            entry.put("compareAt", shopPrices.show(variant.compareAtPrice()));
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
