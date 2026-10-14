package com.unispark.ecommerce.media;

/**
 * The WebP renditions stored for every image, matching the storefront's responsive {@code srcset} widths
 * (SPEC 9.5). Images narrower than a rendition are stored at their own width, never upscaled.
 */
public enum ImageSize {
    SMALL(320),
    MEDIUM(640),
    LARGE(1024);

    private final int width;

    ImageSize(int width) {
        this.width = width;
    }

    public int width() {
        return width;
    }
}
