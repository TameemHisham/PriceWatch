package com.tameem.pricewatch.scraper;

/**
 * One hit from a storefront's search results: just enough to fetch and match it.
 * Deliberately title and URL only — price and availability come from scraping the
 * product page properly, not from a results tile.
 */
public record SearchResult(String title, String url) {}
