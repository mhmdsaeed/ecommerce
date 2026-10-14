package com.unispark.ecommerce.media;

import java.io.InputStream;
import java.util.UUID;

/**
 * Stores public images (product photos) as WebP renditions and builds their URLs.
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
