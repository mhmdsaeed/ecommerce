package com.unispark.ecommerce.catalog.internal;

import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.unispark.ecommerce.catalog.ProductImages;
import com.unispark.ecommerce.catalog.internal.persistence.ProductImage;
import com.unispark.ecommerce.catalog.internal.persistence.ProductRepository;
import com.unispark.ecommerce.media.ImageDiscarded;
import com.unispark.ecommerce.media.ImageStore;
import com.unispark.ecommerce.media.ImageStore.Stored;
import com.unispark.ecommerce.shared.TenantContext;

/**
 * Product photos. Processing and uploading an image takes seconds, so it happens outside any transaction; the
 * row is inserted afterwards. If that fails, the stored renditions are discarded again.
 */
@Service
class ProductImageService implements ProductImages {

    private final ProductRepository products;
    private final ImageStore store;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;

    ProductImageService(ProductRepository products, ImageStore store, ApplicationEventPublisher events,
            TransactionTemplate transaction) {
        this.products = products;
        this.store = store;
        this.events = events;
        this.transaction = transaction;
    }

    @Override
    public ImageResult add(UUID productId, InputStream upload) {
        // Cheap checks first, so a full gallery does not cost an image decode and upload.
        int existingImages = transaction.execute(status -> products.findById(productId)
                .map(product -> product.getImages().size())
                .orElse(-1));
        if (existingImages < 0) {
            return new NotFound();
        }
        if (existingImages >= MAX_IMAGES) {
            return new LimitReached();
        }
        var stored = store.storePublic(TenantContext.requireTenantId(), upload);
        if (stored instanceof ImageStore.Rejected(var reason)) {
            return new Rejected(reason);
        }
        var image = (Stored) stored;
        try {
            var result = transaction.execute(status -> attach(productId, image));
            if (!(result instanceof Added)) {
                discard(image.key());
            }
            return result;
        } catch (RuntimeException e) {
            discard(image.key());
            throw e;
        }
    }

    private ImageResult attach(UUID productId, Stored stored) {
        var found = products.findById(productId);
        if (found.isEmpty()) {
            return new NotFound();
        }
        var product = found.get();
        var images = product.getImages();
        if (images.size() >= MAX_IMAGES) {
            return new LimitReached();
        }
        var sortOrder = images.stream().mapToInt(ProductImage::getSortOrder).max().orElse(-1) + 1;
        var image = new ProductImage(product, stored.key(), stored.width(), stored.height(), sortOrder);
        images.add(image);
        return new Added(CatalogViews.image(image));
    }

    @Override
    public ImageResult remove(UUID productId, UUID imageId) {
        return transaction.execute(status -> products.findById(productId).<ImageResult>map(product -> {
            var image = product.getImages().stream().filter(candidate -> candidate.getId().equals(imageId)).findFirst();
            if (image.isEmpty()) {
                return new NotFound();
            }
            product.getImages().remove(image.get());
            events.publishEvent(new ImageDiscarded(image.get().getStorageKey()));
            renumber(product.getImages());
            return new Changed();
        }).orElseGet(NotFound::new));
    }

    @Override
    public ImageResult move(UUID productId, UUID imageId, int direction) {
        return transaction.execute(status -> products.findById(productId).<ImageResult>map(product -> {
            var images = product.getImages().stream().sorted(Comparator.comparingInt(ProductImage::getSortOrder))
                    .toList();
            var index = -1;
            for (int i = 0; i < images.size(); i++) {
                if (images.get(i).getId().equals(imageId)) {
                    index = i;
                }
            }
            if (index < 0) {
                return new NotFound();
            }
            var target = index + Integer.signum(direction);
            if (target >= 0 && target < images.size()) {
                var moved = images.get(index);
                var other = images.get(target);
                var order = moved.getSortOrder();
                moved.setSortOrder(other.getSortOrder());
                other.setSortOrder(order);
            }
            return new Changed();
        }).orElseGet(NotFound::new));
    }

    private static void renumber(List<ProductImage> images) {
        var sorted = images.stream().sorted(Comparator.comparingInt(ProductImage::getSortOrder)).toList();
        for (int i = 0; i < sorted.size(); i++) {
            sorted.get(i).setSortOrder(i);
        }
    }

    /** In its own transaction: the discard listener only runs for events published inside one. */
    private void discard(String key) {
        transaction.executeWithoutResult(status -> events.publishEvent(new ImageDiscarded(key)));
    }
}
