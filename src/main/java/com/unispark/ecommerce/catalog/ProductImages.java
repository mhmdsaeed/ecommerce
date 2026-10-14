package com.unispark.ecommerce.catalog;

import java.io.InputStream;
import java.util.UUID;

import com.unispark.ecommerce.catalog.Products.ImageView;
import com.unispark.ecommerce.media.ImageStore;

/** Photos of the current store's products, in display order. At most {@value #MAX_IMAGES} per product. */
public interface ProductImages {

    int MAX_IMAGES = 10;

    ImageResult add(UUID productId, InputStream upload);

    ImageResult remove(UUID productId, UUID imageId);

    /** Moves an image one place earlier ({@code -1}) or later ({@code +1}). */
    ImageResult move(UUID productId, UUID imageId, int direction);

    sealed interface ImageResult {
    }

    record Added(ImageView image) implements ImageResult {
    }

    record Changed() implements ImageResult {
    }

    record Rejected(ImageStore.Reason reason) implements ImageResult {
    }

    record LimitReached() implements ImageResult {
    }

    record NotFound() implements ImageResult {
    }
}
