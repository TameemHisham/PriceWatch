package com.tameem.pricewatch.matching;

import com.tameem.pricewatch.config.MarketplaceRegistry;
import com.tameem.pricewatch.config.ScrapeProperties;
import com.tameem.pricewatch.config.ScraperRegistry;
import com.tameem.pricewatch.entity.Store;
import com.tameem.pricewatch.scraper.ProductData;
import com.tameem.pricewatch.scraper.ScraperRateLimiter;
import com.tameem.pricewatch.scraper.SearchResult;
import com.tameem.pricewatch.scraper.SearchableScraper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fan-out selection in {@link CrossStoreDiscovery}. A marketplace turned off in configuration
 * must not be searched — the whole point of the disable flag is to stop spending requests on a
 * storefront the scrapers cannot reach (B&amp;H behind Cloudflare), without deleting its scraper.
 */
class CrossStoreDiscoveryTest {

    private static final String ENABLED_ID = "NEWEGG";
    private static final String DISABLED_ID = "BH_PHOTO";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /** Records whether its search() was called, so the test can assert it never runs. */
    private static final class RecordingScraper implements SearchableScraper {
        private final String marketplaceId;
        final AtomicBoolean searched = new AtomicBoolean(false);

        RecordingScraper(String marketplaceId) {
            this.marketplaceId = marketplaceId;
        }

        @Override
        public List<SearchResult> search(String query) {
            searched.set(true);
            return List.of();
        }

        @Override
        public boolean supports(String id) {
            return marketplaceId.equals(id);
        }

        @Override public ProductData scrape(String url) { throw new UnsupportedOperationException(); }
        @Override public Optional<String> productKey(String url) { return Optional.empty(); }
        @Override public Store store() { return Store.NEWEGG; }
        @Override public String canonicalUrl(String url) { return url; }
    }

    private static MarketplaceRegistry registryWith(boolean bhEnabled) {
        ScrapeProperties properties = new ScrapeProperties();
        properties.getMarketplaces().put(ENABLED_ID, config("newegg.com", true));
        properties.getMarketplaces().put(DISABLED_ID, config("bhphotovideo.com", bhEnabled));
        return new MarketplaceRegistry(properties);
    }

    private static ScrapeProperties.MarketplaceConfig config(String host, boolean enabled) {
        ScrapeProperties.MarketplaceConfig config = new ScrapeProperties.MarketplaceConfig();
        config.setHost(host);
        config.setEnabled(enabled);
        return config;
    }

    private CrossStoreDiscovery discoveryOver(MarketplaceRegistry marketplaces,
                                              SearchableScraper... scrapers) {
        return discoveryOver(marketplaces, new ScraperRateLimiter(), scrapers);
    }

    private CrossStoreDiscovery discoveryOver(MarketplaceRegistry marketplaces,
                                              ScraperRateLimiter rateLimiter,
                                              SearchableScraper... scrapers) {
        ScraperRegistry scraperRegistry = new ScraperRegistry(List.of(scrapers), marketplaces);
        AttributeExtractor extractor = mock(AttributeExtractor.class);
        when(extractor.extract(any())).thenReturn(Optional.empty());
        ProductMatcher matcher = mock(ProductMatcher.class);
        return new CrossStoreDiscovery(scraperRegistry, marketplaces, extractor, matcher,
                executor, rateLimiter);
    }

    @Test
    void doesNotSearchADisabledMarketplace() {
        RecordingScraper enabled = new RecordingScraper(ENABLED_ID);
        RecordingScraper disabled = new RecordingScraper(DISABLED_ID);
        CrossStoreDiscovery discovery = discoveryOver(registryWith(false), enabled, disabled);

        Map<String, SearchResult> matches = discovery.findMatches("Sony WH-1000XM5");

        assertTrue(enabled.searched.get(), "the enabled marketplace should still be searched");
        assertFalse(disabled.searched.get(),
                "a disabled marketplace must not be searched during discovery");
        assertFalse(matches.containsKey(DISABLED_ID), "a disabled marketplace must never be matched");
    }

    /** Guards against the filter being a no-op: flip B&H back on and it is searched again. */
    @Test
    void searchesTheSameMarketplaceOnceReEnabled() {
        RecordingScraper reEnabled = new RecordingScraper(DISABLED_ID);
        CrossStoreDiscovery discovery = discoveryOver(registryWith(true), reEnabled);

        discovery.findMatches("Sony WH-1000XM5");

        assertTrue(reEnabled.searched.get(),
                "an enabled marketplace should be searched — proves the skip is the flag, not the id");
    }

    /** A marketplace whose breaker is OPEN is skipped even though it is enabled. */
    @Test
    void doesNotSearchAMarketplaceWithAnOpenBreaker() {
        ScraperRateLimiter rateLimiter = new ScraperRateLimiter();
        rateLimiter.recordEdgeChallenge(ENABLED_ID, "Cloudflare JS challenge — not retryable");

        RecordingScraper open = new RecordingScraper(ENABLED_ID);
        CrossStoreDiscovery discovery = discoveryOver(registryWith(true), rateLimiter, open);

        discovery.findMatches("Sony WH-1000XM5");

        assertFalse(open.searched.get(),
                "a marketplace with an open circuit breaker must not be searched");
    }

    /** enabled=false wins over a closed breaker: a disabled store is skipped regardless. */
    @Test
    void disabledWinsOverAClosedBreaker() {
        ScraperRateLimiter rateLimiter = new ScraperRateLimiter(); // all breakers CLOSED
        RecordingScraper disabled = new RecordingScraper(DISABLED_ID);
        CrossStoreDiscovery discovery = discoveryOver(registryWith(false), rateLimiter, disabled);

        discovery.findMatches("Sony WH-1000XM5");

        assertFalse(disabled.searched.get(),
                "the manual disable flag must skip the store even with a closed breaker");
    }
}
