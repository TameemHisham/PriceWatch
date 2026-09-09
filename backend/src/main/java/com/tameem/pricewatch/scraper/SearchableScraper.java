package com.tameem.pricewatch.scraper;

import java.util.List;

/**
 * A scraper whose storefront can also be searched by keyword.
 * <p>
 * Separate from {@link ProductScraper} on purpose. Most storefronts cannot be searched
 * with Jsoup at all — Amazon answers search with a 202 stub, Newegg with a CAPTCHA, and
 * IKEA, Jarir and eXtra render results client-side — so "cannot search" is the normal
 * case, not a failure. Making it a capability that a scraper either has or does not keeps
 * that distinguishable from "searched and found nothing", which a default method
 * returning an empty list would blur.
 */
public interface SearchableScraper extends ProductScraper {

    /** Product hits for a keyword query, newest-relevance first, or empty when none. */
    List<SearchResult> search(String query);
}
