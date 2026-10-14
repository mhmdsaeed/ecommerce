package com.unispark.ecommerce.media;

/**
 * Nothing references this image any more; its stored renditions can be deleted. Persisted in the event
 * publication registry, so the deletion is retried if storage is down when the transaction commits.
 */
public record ImageDiscarded(String key) {
}
