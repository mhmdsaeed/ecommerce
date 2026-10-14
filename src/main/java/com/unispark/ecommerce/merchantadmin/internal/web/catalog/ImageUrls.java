package com.unispark.ecommerce.merchantadmin.internal.web.catalog;

import org.springframework.stereotype.Component;

import com.unispark.ecommerce.media.ImageSize;
import com.unispark.ecommerce.media.ImageStore;

/** Rendition URLs for templates: {@code ${@imageUrls.small(key)}}. */
@Component("imageUrls")
class ImageUrls {

    private final ImageStore images;

    ImageUrls(ImageStore images) {
        this.images = images;
    }

    public String small(String key) {
        return key == null ? null : images.url(key, ImageSize.SMALL);
    }

    public String medium(String key) {
        return key == null ? null : images.url(key, ImageSize.MEDIUM);
    }
}
