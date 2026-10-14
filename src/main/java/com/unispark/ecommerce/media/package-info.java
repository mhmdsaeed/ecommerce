/**
 * Uploads, storage and image URLs (SPEC 3, 9.5, 10). Images are re-encoded server-side into WebP renditions and
 * stored under random keys in S3-compatible storage (MinIO locally). Public API: {@link
 * com.unispark.ecommerce.media.ImageStore} and the {@link com.unispark.ecommerce.media.ImageDiscarded} event.
 */
@ApplicationModule(displayName = "Media")
package com.unispark.ecommerce.media;

import org.springframework.modulith.ApplicationModule;
