package com.tameem.pricewatch.entity;

/**
 * Where a product is in its cross-store discovery. Since discovery moved off the track
 * request onto Kafka (Phase 5), this is the only way anything — the frontend, a benchmark,
 * your own eyes — can tell whether discovery has finished.
 *
 * PENDING — tracked, discovery event published, not yet processed.
 * DONE    — discovery finished (whether or not it attached anything).
 * FAILED  — discovery was retried and dead-lettered (set in the reliability step).
 */
public enum DiscoveryStatus {
    PENDING,
    DONE,
    FAILED
}
