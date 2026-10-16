package com.unispark.ecommerce.storefront.internal.crawler;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriUtils;

import com.unispark.ecommerce.catalog.StorefrontCatalog;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.Sites;

/**
 * What search engines read (SPEC 9.5): {@code robots.txt} on every host, and a {@code sitemap.xml} per store with
 * its home, categories and products. Stores are reached on their own host, so the addresses are built from the
 * request; a store not launched yet never gets here (its storefront is closed).
 *
 * <p>Kept outside the storefront web package on purpose: StorefrontAdvice there issues the XSRF-TOKEN cookie,
 * which must never ride on these publicly cacheable responses (a shared cache would hand one token to everyone).
 */
@Controller
class CrawlerController {

    /** The sitemap protocol allows 50,000 addresses per file; a store stays far below that. */
    static final int MAX_PRODUCTS = 45_000;
    static final String CACHE = "public, max-age=3600";

    private final Sites sites;
    private final StorefrontCatalog catalog;

    CrawlerController(Sites sites, StorefrontCatalog catalog) {
        this.sites = sites;
        this.catalog = catalog;
    }

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    String robots(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE);
        return switch (sites.siteOf(request)) {
            // Shopping steps and personal pages say nothing to search engines.
            case STOREFRONT -> """
                    User-agent: *
                    Disallow: /cart
                    Disallow: /checkout
                    Disallow: /orders/
                    Disallow: /search
                    Disallow: /lang/

                    Sitemap: %s/sitemap.xml
                    """.formatted(base(request));
            case MAIN -> """
                    User-agent: *
                    Disallow: /admin/
                    Disallow: /driver/
                    """;
            case PLATFORM_ADMIN -> """
                    User-agent: *
                    Disallow: /
                    """;
        };
    }

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    String sitemap(HttpServletRequest request, HttpServletResponse response) {
        if (TenantContext.currentTenantId().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE);
        var base = base(request);
        var entries = catalog.sitemap(MAX_PRODUCTS);
        var xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                """);
        url(xml, base + "/", null);
        for (var slug : entries.categories()) {
            url(xml, base + "/c/" + UriUtils.encodePathSegment(slug, StandardCharsets.UTF_8), null);
        }
        for (var product : entries.products()) {
            url(xml, base + "/p/" + UriUtils.encodePathSegment(product.slug(), StandardCharsets.UTF_8),
                    DateTimeFormatter.ISO_LOCAL_DATE.format(product.updatedAt().atOffset(ZoneOffset.UTC)));
        }
        return xml.append("</urlset>\n").toString();
    }

    private static void url(StringBuilder xml, String location, String lastModified) {
        xml.append("  <url><loc>").append(HtmlUtils.htmlEscape(location)).append("</loc>");
        if (lastModified != null) {
            xml.append("<lastmod>").append(lastModified).append("</lastmod>");
        }
        xml.append("</url>\n");
    }

    /** Scheme, host and port as the visitor used them. */
    private static String base(HttpServletRequest request) {
        var url = request.getRequestURL();
        return url.substring(0, url.length() - request.getRequestURI().length());
    }
}
