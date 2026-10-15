package com.unispark.ecommerce.media;

import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;

/**
 * Stores images as WebP renditions and builds their URLs: public ones (product photos, logos) and private ones
 * (payment screenshots).
 *
 * <p>Uploads are checked by content, not by the browser's content type or file name: only JPEG, PNG and WebP, at
 * most {@code app.media.max-upload-bytes} and {@code app.media.max-pixels}. They are always re-encoded, which
 * also drops metadata such as GPS coordinates, and phone photos are turned upright from their EXIF orientation.
 *
 * <p>To remove an image, publish {@link ImageDiscarded} in the transaction that drops the last reference to it.
 */
public interface ImageStore {

    StoreResult storePublic(UUID tenantId, InputStream upload);

    /** The URL of one rendition, e.g. for {@code src} and {@code srcset}. */
    String url(String key, ImageSize size);

    /**
     * Stores a private image, such as a payment screenshot (SPEC 10): one {@link ImageSize#LARGE} rendition that
     * is not publicly readable. Show it with {@link #signedUrl}.
     */
    StoreResult storePrivate(UUID tenantId, InputStream upload);

    /** A link to a private image that works for {@code validFor}, then expires. */
    String signedUrl(String key, Duration validFor);

    sealed interface StoreResult {
    }

    /** {@code key} identifies all renditions; width and height are those of the upright original. */
    record Stored(String key, int width, int height) implements StoreResult {
    }

    record Rejected(Reason reason) implements StoreResult {
    }

    enum Reason {
        UNSUPPORTED_TYPE,
        TOO_LARGE,
        TOO_MANY_PIXELS,
        UNREADABLE,
        /** The image was fine but storage was unreachable; trying again later may work. */
        STORAGE_FAILED
    }
}
