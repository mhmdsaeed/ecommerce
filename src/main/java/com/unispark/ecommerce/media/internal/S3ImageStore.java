package com.unispark.ecommerce.media.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.unispark.ecommerce.media.ImageDiscarded;
import com.unispark.ecommerce.media.ImageSize;
import com.unispark.ecommerce.media.ImageStore;
import com.unispark.ecommerce.media.internal.ImageProcessor.Failed;
import com.unispark.ecommerce.media.internal.ImageProcessor.Processed;
import com.unispark.ecommerce.shared.Ids;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

/**
 * Stores renditions at {@code public/<tenant>/<random id>/<width>.webp}. Keys never contain anything the user sent.
 * Renditions are immutable, so they are served with a one-year cache lifetime (SPEC 9.5).
 */
@Component
class S3ImageStore implements ImageStore {

    static final String PUBLIC_PREFIX = "public/";
    static final String CACHE_FOREVER = "public, max-age=31536000, immutable";

    private static final Logger log = LoggerFactory.getLogger(S3ImageStore.class);

    private final S3Client s3;
    private final MediaProperties properties;
    private final ImageProcessor processor;

    S3ImageStore(S3Client s3, MediaProperties properties, ImageProcessor processor) {
        this.s3 = s3;
        this.properties = properties;
        this.processor = processor;
    }

    @Override
    public StoreResult storePublic(UUID tenantId, InputStream upload) {
        ImageProcessor.Outcome outcome;
        try {
            outcome = processor.process(upload);
        } catch (IOException e) {
            return new Rejected(Reason.UNREADABLE);
        }
        return switch (outcome) {
            case Failed(var reason) -> new Rejected(reason);
            case Processed processed -> upload(PUBLIC_PREFIX + tenantId + "/" + Ids.newId(), processed);
        };
    }

    private StoreResult upload(String key, Processed image) {
        var written = new ArrayList<String>();
        try {
            for (var rendition : image.renditions()) {
                var objectKey = objectKey(key, rendition.size());
                s3.putObject(put -> put.bucket(properties.bucket())
                                .key(objectKey)
                                .contentType("image/webp")
                                .cacheControl(CACHE_FOREVER),
                        RequestBody.fromBytes(rendition.webp()));
                written.add(objectKey);
            }
            return new Stored(key, image.width(), image.height());
        } catch (SdkException e) {
            log.error("Could not store image {} in bucket {}", key, properties.bucket(), e);
            deleteQuietly(written);
            return new Rejected(Reason.STORAGE_FAILED);
        }
    }

    @Override
    public String url(String key, ImageSize size) {
        return properties.publicBase() + "/" + objectKey(key, size);
    }

    /**
     * Runs after the discarding transaction commits, on its own thread. A storage error leaves the event
     * publication incomplete, and it is retried on the next restart.
     */
    @ApplicationModuleListener
    void on(ImageDiscarded event) {
        if (!event.key().startsWith(PUBLIC_PREFIX)) {
            throw new IllegalArgumentException("Not a public image key: " + event.key());
        }
        var objects = Arrays.stream(ImageSize.values())
                .map(size -> ObjectIdentifier.builder().key(objectKey(event.key(), size)).build())
                .toList();
        s3.deleteObjects(delete -> delete.bucket(properties.bucket()).delete(Delete.builder().objects(objects).build()));
    }

    static String objectKey(String key, ImageSize size) {
        return key + "/" + size.width() + ".webp";
    }

    private void deleteQuietly(List<String> keys) {
        for (var key : keys) {
            try {
                s3.deleteObject(delete -> delete.bucket(properties.bucket()).key(key));
            } catch (SdkException e) {
                log.warn("Could not clean up {} after a failed upload", key, e);
            }
        }
    }
}
