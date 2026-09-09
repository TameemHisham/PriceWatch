package com.tameem.pricewatch.matching;

import com.tameem.pricewatch.config.MarketplaceRegistry;
import com.tameem.pricewatch.config.ScraperRegistry;
import com.tameem.pricewatch.scraper.ScrapeException;
import com.tameem.pricewatch.scraper.SearchResult;
import com.tameem.pricewatch.scraper.SearchableScraper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the same product on other storefronts, given a title.
 * <p>
 * Searches every storefront that can be searched, then puts each hit through the attribute
 * gate. Only outright matches are returned: a rejected or uncertain candidate is dropped,
 * never attached, because a wrong listing silently corrupts a product's price history.
 */
@Service
public class CrossStoreDiscovery {

    private static final Logger log = LoggerFactory.getLogger(CrossStoreDiscovery.class);

    private final ScraperRegistry scrapers;
    private final MarketplaceRegistry marketplaces;
    private final AttributeExtractor extractor;
    private final ProductMatcher matcher;

    public CrossStoreDiscovery(ScraperRegistry scrapers, MarketplaceRegistry marketplaces,
                               AttributeExtractor extractor, ProductMatcher matcher) {
        this.scrapers = scrapers;
        this.marketplaces = marketplaces;
        this.extractor = extractor;
        this.matcher = matcher;
    }

    /**
     * At most one confirmed match per marketplace, keyed by marketplace id.
     * <p>
     * One per marketplace because a product carries at most one listing per marketplace —
     * a second would violate the listing's uniqueness constraint — and because two hits
     * from the same store that both pass the gate are usually variants of one another.
     *
     * @param title              the product title to match against
     * @param excludeMarketplaces marketplaces already attached, skipped entirely
     */
    public Map<String, SearchResult> findMatches(String title, List<String> excludeMarketplaces) {
        Map<String, SearchResult> matches = new LinkedHashMap<>();
        if (title == null || title.isBlank()) {
            return matches;
        }

        List<SearchableScraper> searchable = scrapers.searchable();
        if (searchable.isEmpty()) {
            log.debug("No searchable storefronts configured — cross-store discovery skipped");
            return matches;
        }

        Optional<ProductAttributes> queryAttributes = extractor.extract(title);

        for (SearchableScraper scraper : searchable) {
            if (servesOnlyExcluded(scraper, excludeMarketplaces)) {
                // Every marketplace this scraper covers is already attached. Searching it
                // would spend a request to produce hits the loop below only discards.
                log.debug("Skipping search on {} — its marketplaces are already attached",
                        scraper.getClass().getSimpleName());
                continue;
            }
            List<SearchResult> hits;
            try {
                hits = scraper.search(title);
            } catch (ScrapeException e) {
                // One store refusing to be searched must not fail the whole track.
                log.warn("Search failed on {}: {}", scraper.getClass().getSimpleName(), e.toString());
                continue;
            }

            for (SearchResult hit : hits) {
                String marketplaceId;
                try {
                    marketplaceId = marketplaces.idFor(hit.url());
                } catch (RuntimeException e) {
                    continue; // a hit pointing off the configured storefronts
                }
                if (excludeMarketplaces.contains(marketplaceId) || matches.containsKey(marketplaceId)) {
                    continue;
                }

                ProductMatcher.Outcome outcome = matcher.match(
                        queryAttributes, extractor.extract(hit.title()), title, hit.title());
                if (outcome.isMatch()) {
                    log.info("Matched '{}' on {} — {}", hit.title(), marketplaceId, outcome.reason());
                    matches.put(marketplaceId, hit);
                } else {
                    log.debug("Skipped '{}' on {} — {}: {}", hit.title(), marketplaceId,
                            outcome.decision(), outcome.reason());
                }
            }
        }
        return matches;
    }

    /** True when every marketplace this scraper serves is already attached to the product. */
    private boolean servesOnlyExcluded(SearchableScraper scraper, List<String> excluded) {
        List<String> served = marketplaces.allMarketplaceIds().stream()
                .filter(scraper::supports)
                .toList();
        return !served.isEmpty() && excluded.containsAll(served);
    }

    /** Convenience for the no-exclusions case. */
    public Map<String, SearchResult> findMatches(String title) {
        return findMatches(title, new ArrayList<>());
    }
}
