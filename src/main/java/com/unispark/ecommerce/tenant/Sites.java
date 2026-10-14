package com.unispark.ecommerce.tenant;

import jakarta.servlet.http.HttpServletRequest;

/** Classifies requests by host. Security chains use it to keep each site's routes on its own host. */
public interface Sites {

    Site siteOf(HttpServletRequest request);

    /** The storefront address for a slug, e.g. {@code demo1.lvh.me:8080}; shown next to the slug in forms. */
    String storeHost(String slug);

    /** The base domain without port, e.g. {@code lvh.me}. */
    String baseHost();
}
