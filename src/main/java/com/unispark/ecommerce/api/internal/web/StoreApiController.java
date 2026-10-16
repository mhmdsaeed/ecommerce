package com.unispark.ecommerce.api.internal.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.unispark.ecommerce.api.internal.web.ApiViews.StoreResponse;
import com.unispark.ecommerce.media.ImageSize;
import com.unispark.ecommerce.media.ImageStore;
import com.unispark.ecommerce.tenant.Sites;
import com.unispark.ecommerce.tenant.StoreLookup;
import com.unispark.ecommerce.tenant.StoreSettings;
import com.unispark.ecommerce.tenant.StoreStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/stores")
@Tag(name = "Stores")
class StoreApiController {

    private final StoreLookup stores;
    private final StoreSettings settings;
    private final ImageStore images;
    private final Sites sites;

    StoreApiController(StoreLookup stores, StoreSettings settings, ImageStore images, Sites sites) {
        this.stores = stores;
        this.settings = settings;
        this.images = images;
        this.sites = sites;
    }

    @GetMapping("/{slug}")
    @Operation(summary = "A store open to shoppers, by its slug",
            description = "The app sends the returned id as X-Store-Id on catalog requests.")
    @ApiResponse(responseCode = "200", description = "The store")
    @ApiResponse(responseCode = "404", description = "Unknown, closed or not launched yet")
    @ApiResponse(responseCode = "503", description = "Temporarily unavailable (suspended)")
    StoreResponse store(@PathVariable String slug, HttpServletRequest request) {
        var store = stores.findBySlug(slug).filter(found -> found.status().hasStorefront() && found.launched())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such store."));
        if (store.status() == StoreStatus.SUSPENDED) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "This store is temporarily unavailable.");
        }
        var current = settings.of(store.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such store."));
        var theme = current.theme();
        var logo = theme == null || theme.logoKey() == null ? null : images.url(theme.logoKey(), ImageSize.SMALL);
        return new StoreResponse(store.id(), store.slug(), current.name(), current.defaultLocale().getLanguage(),
                current.displayCurrency(), theme == null ? null : theme.primaryColor(), logo, current.contactPhone(),
                current.whatsapp(), request.getScheme() + "://" + sites.storeHost(store.slug()) + "/");
    }
}
