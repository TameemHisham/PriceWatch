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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Service
public class CrossStoreDiscovery {

    private static final Logger log = LoggerFactory.getLogger(CrossStoreDiscovery.class);

    private final ScraperRegistry scrapers;
    private final MarketplaceRegistry marketplaces;
    private final AttributeExtractor extractor;
    private final ProductMatcher matcher;
    private final ExecutorService scraperExecutor;

    public CrossStoreDiscovery(ScraperRegistry scrapers, MarketplaceRegistry marketplaces,
                               AttributeExtractor extractor, ProductMatcher matcher,
                               ExecutorService scraperExecutor) {
        this.scrapers = scrapers;
        this.marketplaces = marketplaces;
        this.extractor = extractor;
        this.matcher = matcher;
        this.scraperExecutor = scraperExecutor;
    }

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

        // Step 1: decide which scrapers are even worth calling — cheap, no I/O, stays sequential.
        List<SearchableScraper> toSearch = searchable.stream()
                .filter(scraper -> {
                    if (servesOnlyDisabled(scraper)) {
                        log.debug("Skipping search on {} — all its marketplaces are disabled",
                                scraper.getClass().getSimpleName());
                        return false;
                    }
                    if (servesOnlyExcluded(scraper, excludeMarketplaces)) {
                        log.debug("Skipping search on {} — its marketplaces are already attached",
                                scraper.getClass().getSimpleName());
                        return false;
                    }
                    return true;
                })
                .toList();

        // Step 2: fire every remaining store's search at the same time.
        List<CompletableFuture<List<SearchResult>>> futures = toSearch.stream()
                .map(scraper -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return scraper.search(title);
                    } catch (ScrapeException e) {
                        log.warn("Search failed on {}: {}", scraper.getClass().getSimpleName(), e.toString());
                        return List.<SearchResult>of();
                    }
                }, scraperExecutor))
                .toList();

        // Step 3: wait for every search to finish, collect results in the same order as toSearch.
        List<List<SearchResult>> allHits = futures.stream().map(CompletableFuture::join).toList();

        // Step 4: match each hit against the query, sequentially — cheap, and matches isn't
        // thread-safe, so this has to happen back on one thread regardless.
        Optional<ProductAttributes> queryAttributes = extractor.extract(title);
        for (List<SearchResult> hits : allHits) {
            for (SearchResult hit : hits) {
                String marketplaceId;
                try {
                    marketplaceId = marketplaces.idFor(hit.url());
                } catch (RuntimeException e) {
                    continue;
                }
                if (excludeMarketplaces.contains(marketplaceId) || matches.containsKey(marketplaceId)
                        || !marketplaces.isEnabled(marketplaceId)) {
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

    private boolean servesOnlyExcluded(SearchableScraper scraper, List<String> excluded) {
        List<String> served = marketplaces.allMarketplaceIds().stream()
                .filter(scraper::supports)
                .toList();
        return !served.isEmpty() && excluded.containsAll(served);
    }

    /**
     * Whether every marketplace this scraper serves is disabled, so calling its search would
     * only spend a request on a storefront no hit could be attached from. A multi-region
     * scraper with one live region is still worth searching.
     */
    private boolean servesOnlyDisabled(SearchableScraper scraper) {
        List<String> served = marketplaces.allMarketplaceIds().stream()
                .filter(scraper::supports)
                .toList();
        return !served.isEmpty() && served.stream().noneMatch(marketplaces::isEnabled);
    }

    public Map<String, SearchResult> findMatches(String title) {
        return findMatches(title, new ArrayList<>());
    }
}