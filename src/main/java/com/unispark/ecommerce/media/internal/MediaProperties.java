package com.unispark.ecommerce.media.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * S3-compatible storage settings (SPEC 15: {@code S3_*}). {@code endpoint} is blank for AWS S3 itself and set for
 * MinIO or Cloudflare R2. {@code manageBucket} creates the bucket and its public-read policy for {@code public/}
 * on startup; turn it on for local and test, and manage the bucket outside the app in production.
 */
@ConfigurationProperties("app.media")
record MediaProperties(
        String endpoint,
        @DefaultValue("us-east-1") String region,
        String bucket,
        String accessKey,
        String secretKey,
        String publicBaseUrl,
        @DefaultValue("false") boolean manageBucket,
        @DefaultValue("5242880") long maxUploadBytes,
        @DefaultValue("100000000") long maxPixels,
        @DefaultValue("2") int concurrentDecodes) {

    String publicBase() {
        return publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
    }
}
