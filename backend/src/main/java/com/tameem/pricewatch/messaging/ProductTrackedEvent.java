package com.tameem.pricewatch.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * "A product was tracked — go discover it on other stores." Deliberately minimal: the DB is
 * the source of truth, so the consumer re-reads the product by id rather than trusting a
 * snapshot that could already be stale by the time it is processed.
 *
 * Used twice: published in-process as a Spring event (so it can wait for the DB commit),
 * then serialized to JSON and sent to Kafka by {@link ProductTrackedPublisher}.
 */
public record ProductTrackedEvent(UUID eventId, long trackedProductId, Instant occurredAt) {

    public static ProductTrackedEvent of(long trackedProductId) {
        return new ProductTrackedEvent(UUID.randomUUID(), trackedProductId, Instant.now());
    }
}
