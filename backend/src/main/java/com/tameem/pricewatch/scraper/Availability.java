package com.tameem.pricewatch.scraper;

/**
 * Whether the retailer is currently offering this listing to the requesting
 * location. Distinct from a scrape failure: UNAVAILABLE is a successful
 * observation that there is no purchasable offer, so there is no price to read.
 * Availability is location-dependent — a UK-only item has no offer for a request
 * that appears to come from elsewhere.
 */
public enum Availability {
    AVAILABLE,
    UNAVAILABLE
}
