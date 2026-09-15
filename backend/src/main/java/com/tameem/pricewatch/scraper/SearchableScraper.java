package com.tameem.pricewatch.scraper;

import java.util.List;

/**
 * A scraper whose storefront can also be searched by keyword.
 * <p>
 * Separate from {@link ProductScraper} on purpose. Several storefronts cannot be searched
 * at all — Amazon answers search with a 202 stub, Newegg with a CAPTCHA, and eXtra renders
 * results client-side — so "cannot search" is a normal case, not a failure. Making it a
 * capability that a scraper either has or does not keeps that distinguishable from
 * "searched and found nothing", which a default method returning an empty list would blur.
 * <p>
 * Implementing this is what puts a storefront into cross-store discovery: {@code
 * CrossStoreDiscovery} iterates {@code ScraperRegistry.searchable()} and nothing else, so a
 * scraper that only implements {@link ProductScraper} can be tracked from a pasted URL but
 * will never be found on its own. IKEA and Jarir spent a long time in exactly that state.
 * <p>
 * A client-rendered results page does not by itself mean unsearchable. IKEA and Jarir both
 * serve a results shell with no product links in it, and both are searched here through the
 * JSON endpoint their own search box calls — which returns the product URL outright and is
 * steadier than any results-page selector.
 */
public interface SearchableScraper extends ProductScraper {

    /** Product hits for a keyword query, newest-relevance first, or empty when none. */
    List<SearchResult> search(String query);
}
