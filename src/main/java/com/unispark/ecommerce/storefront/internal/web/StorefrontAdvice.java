package com.unispark.ecommerce.storefront.internal.web;


import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.unispark.ecommerce.catalog.StorefrontCatalog;
import com.unispark.ecommerce.media.ImageSize;
import com.unispark.ecommerce.media.ImageStore;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreSettings;

/**
 * Everything the storefront layout needs on every page: the store, its navigation and theme. Also marks the page
 * as a storefront page (no CSRF token in the page; it travels in a cookie) and lets browsers reuse it for a
 * minute. Not shared caches: the language comes from a cookie, so the same URL differs per visitor.
 */
@ControllerAdvice(basePackageClasses = StorefrontAdvice.class)
class StorefrontAdvice {

    static final String CACHE = "private, max-age=60";

    private final StoreSettings settings;
    private final StorefrontCatalog catalog;
    private final ImageStore images;

    StorefrontAdvice(StoreSettings settings, StorefrontCatalog catalog, ImageStore images) {
        this.settings = settings;
        this.catalog = catalog;
        this.images = images;
    }

    @ModelAttribute
    void storefront(Model model, HttpServletRequest request, HttpServletResponse response) {
        model.addAttribute("storefrontPage", true);
        if ("GET".equals(request.getMethod())) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE);
        }
        if (TenantContext.currentTenantId().isEmpty()) {
            return;
        }
        // Issues the XSRF-TOKEN cookie on a shopper's first page (only then: an existing cookie is reused), so
        // app.js can send it with the cart's HTMX requests. The page itself never contains the token. Stores only:
        // the main site keeps its token in the session.
        if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken csrf) {
            csrf.getToken();
        }
        var shop = settings.current();
        model.addAttribute("shop", shop);
        model.addAttribute("themeStyle", ThemeStyle.css(shop.theme()));
        model.addAttribute("logoUrl", shop.theme().logoKey() == null ? null
                : images.url(shop.theme().logoKey(), ImageSize.SMALL));
        model.addAttribute("navigation", catalog.navigation());
    }
}
