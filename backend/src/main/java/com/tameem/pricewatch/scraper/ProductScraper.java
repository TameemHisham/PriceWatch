package com.tameem.pricewatch.scraper;

import com.tameem.pricewatch.entity.Store;

import java.util.Optional;

public interface ProductScraper {
    public ProductData scrape(String url);
    Optional<String> productKey(String url);

    /**
     * Whether this scraper serves the given configured marketplace id. Each implementation
     * claims its own ids, so adding a storefront does not mean editing a central switch.
     */
    boolean supports(String marketplaceId);

    /** The retailer a listing from this scraper is stored under. */
    Store store();

    /**
     * The canonical, dedupe-stable URL for the product this URL points at, so the same
     * product always normalizes to one string regardless of path shape or query params.
     * <p>
     * Must stay fetchable: stored listings are re-scraped from this value on every sweep.
     */
    String canonicalUrl(String url);
}
