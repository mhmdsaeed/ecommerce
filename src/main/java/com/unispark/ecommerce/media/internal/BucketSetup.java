package com.unispark.ecommerce.media.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/**
 * Local and test setup ({@code app.media.manage-bucket=true}): creates the bucket and lets anyone read
 * {@code public/} so storefronts can link images directly. Logs instead of failing when storage is down, so the
 * rest of the app still starts; uploads then fail with a clear message.
 *
 * <p>The flag is checked at run time, not with {@code @ConditionalOnProperty}: conditions are evaluated before
 * test containers register their properties.
 */
@Component
@Order(1)
class BucketSetup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BucketSetup.class);

    private final S3Client s3;
    private final MediaProperties properties;

    BucketSetup(S3Client s3, MediaProperties properties) {
        this.s3 = s3;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.manageBucket()) {
            return;
        }
        var bucket = properties.bucket();
        try {
            try {
                s3.headBucket(head -> head.bucket(bucket));
            } catch (NoSuchBucketException e) {
                s3.createBucket(create -> create.bucket(bucket));
                log.info("Created media bucket {}", bucket);
            }
            s3.putBucketPolicy(policy -> policy.bucket(bucket).policy(publicReadPolicy(bucket)));
        } catch (SdkException e) {
            log.warn("Media storage at {} is not reachable; image uploads will fail until it is: {}",
                    properties.endpoint(), e.getMessage());
        }
    }

    static String publicReadPolicy(String bucket) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["*"]},\
                "Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/%s*"]}]}""".formatted(bucket, S3ImageStore.PUBLIC_PREFIX);
    }
}
