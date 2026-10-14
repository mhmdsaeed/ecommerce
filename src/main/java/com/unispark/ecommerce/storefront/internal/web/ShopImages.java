package com.unispark.ecommerce.storefront.internal.web;

import org.springframework.stereotype.Component;

import com.unispark.ecommerce.media.ImageSize;
import com.unispark.ecommerce.media.ImageStore;

/** Rendition URLs for storefront templates, as the bean named shopImages. Use through th:with before th:src. */
@Component("shopImages")
class ShopImages {

    private final ImageStore images;

    ShopImages(ImageStore images) {
        this.images = images;
    }

    public String small(String key) {
        return images.url(key, ImageSize.SMALL);
    }

    public String medium(String key) {
        return images.url(key, ImageSize.MEDIUM);
    }

    public String large(String key) {
        return images.url(key, ImageSize.LARGE);
    }
}
