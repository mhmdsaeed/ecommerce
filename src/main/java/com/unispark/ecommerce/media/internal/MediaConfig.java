package com.unispark.ecommerce.media.internal;

import java.net.URI;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaProperties.class)
class MediaConfig {

    @Bean(destroyMethod = "close")
    S3Client s3Client(MediaProperties properties) {
        var builder = S3Client.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())));
        if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
            // MinIO and most S3-compatible stores need path-style addressing.
            builder.endpointOverride(URI.create(properties.endpoint())).forcePathStyle(true);
        }
        return builder.build();
    }

    @Bean
    ImageProcessor imageProcessor(MediaProperties properties) {
        return new ImageProcessor(properties.maxUploadBytes(), properties.maxPixels(), properties.concurrentDecodes());
    }
}
