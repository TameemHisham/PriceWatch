package com.tameem.pricewatch.messaging;

/**
 * "Re-scrape this listing for a fresh price." Produced by the scheduled sweep, one per due
 * listing, keyed by marketplace so every listing of one storefront lands in the same
 * partition — one lane, processed in order by one consumer, which is what lets the 2s
 * politeness pause stay per-marketplace while different marketplaces run concurrently.
 *
 * url and marketplace ride along for logging/routing, but the consumer re-reads the listing
 * by id (the DB is the source of truth).
 */
public record ListingRefreshEvent(long listingId, String marketplace, String url) {}
