package com.unispark.ecommerce.media.internal;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Stores renditions at {@code public/<tenant>/<random id>/<width>.webp}, readable by anyone, and private images at
 * {@code private/<tenant>/<random id>/1024.webp}, readable only through presigned links. Keys never contain
 * anything the user sent. Public renditions are immutable, so they are served with a one-year cache lifetime
 * (SPEC 9.5).
 */
@Component
class S3ImageStore implements ImageStore {

    static final String PUBLIC_PREFIX = "public/";
    static final String PRIVATE_PREFIX = "private/";
    /** Private images are only reached through signed links; browsers keep them for the link's lifetime at most. */
    static final String CACHE_PRIVATE = "private, max-age=300";
    static final String CACHE_FOREVER = "public, max-age=31536000, immutable";

    private static final Logger log = LoggerFactory.getLogger(S3ImageStore.class);

    private final S3Client s3;
    private final MediaProperties properties;
    private final ImageProcessor processor;
    private final S3Presigner presigner;

    S3ImageStore(S3Client s3, MediaProperties properties, ImageProcessor processor, S3Presigner presigner) {
        this.s3 = s3;
        this.properties = properties;
        this.processor = processor;
        this.presigner = presigner;
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

    @Override
    public StoreResult storePrivate(UUID tenantId, InputStream upload) {
        ImageProcessor.Outcome outcome;
        try {
            outcome = processor.process(upload);
        } catch (IOException e) {
            return new Rejected(Reason.UNREADABLE);
        }
        return switch (outcome) {
            case Failed(var reason) -> new Rejected(reason);
            case Processed processed -> upload(PRIVATE_PREFIX + tenantId + "/" + Ids.newId(),
                    new Processed(processed.width(), processed.height(), processed.renditions().stream()
                            .filter(rendition -> rendition.size() == ImageSize.LARGE).toList()),
                    CACHE_PRIVATE);
        };
    }

    @Override
    public String signedUrl(String key, Duration validFor) {
        if (!key.startsWith(PRIVATE_PREFIX)) {
            throw new IllegalArgumentException("Not a private image key: " + key);
        }
        return presigner.presignGetObject(request -> request.signatureDuration(validFor)
                        .getObjectRequest(get -> get.bucket(properties.bucket()).key(objectKey(key, ImageSize.LARGE))))
                .url().toString();
    }

    private StoreResult upload(String key, Processed image) {
        return upload(key, image, CACHE_FOREVER);
    }

    private StoreResult upload(String key, Processed image, String cacheControl) {
        var written = new ArrayList<String>();
        try {
            for (var rendition : image.renditions()) {
                var objectKey = objectKey(key, rendition.size());
                s3.putObject(put -> put.bucket(properties.bucket())
                                .key(objectKey)
                                .contentType("image/webp")
                                .cacheControl(cacheControl),
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

    /** Keys are public/&lt;store&gt;/... and private/&lt;store&gt;/...; anything else is not counted. */
    @Override
    public Optional<Map<UUID, Usage>> usageByStore() {
        var usage = new HashMap<UUID, Usage>();
        try {
            for (var prefix : List.of(PUBLIC_PREFIX, PRIVATE_PREFIX)) {
                s3.listObjectsV2Paginator(list -> list.bucket(properties.bucket()).prefix(prefix)).contents()
                        .forEach(object -> storeOf(object.key(), prefix).ifPresent(store -> usage.merge(store,
                                new Usage(1, object.size()), (one, other) -> new Usage(one.objects() + other.objects(),
                                        one.bytes() + other.bytes()))));
            }
            return Optional.of(Map.copyOf(usage));
        } catch (SdkException e) {
            log.warn("Could not list bucket {} for storage use", properties.bucket(), e);
            return Optional.empty();
        }
    }

    private static Optional<UUID> storeOf(String objectKey, String prefix) {
        var rest = objectKey.substring(prefix.length());
        var slash = rest.indexOf('/');
        try {
            return slash < 0 ? Optional.empty() : Optional.of(UUID.fromString(rest.substring(0, slash)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
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
