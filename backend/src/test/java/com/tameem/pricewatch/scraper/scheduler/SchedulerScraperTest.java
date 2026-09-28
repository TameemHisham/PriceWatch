package com.tameem.pricewatch.scraper.scheduler;

import com.tameem.pricewatch.config.MarketplaceRegistry;
import com.tameem.pricewatch.entity.ProductListing;
import com.tameem.pricewatch.entity.Store;
import com.tameem.pricewatch.repositories.ExchangeRateRepository;
import com.tameem.pricewatch.repositories.ProductListingRepository;
import com.tameem.pricewatch.scraper.ScraperRateLimiter;
import com.tameem.pricewatch.service.TrackedProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A sweep must skip listings on a disabled marketplace and still refresh the rest. The disable
 * flag exists to stop the scheduler wasting a request on a parked storefront (B&amp;H behind
 * Cloudflare) — one DEBUG line a sweep, not a WARN per listing, and never a refresh call.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerScraperTest {

    private final ProductListingRepository listings = mock(ProductListingRepository.class);
    private final TrackedProductService trackedProductService = mock(TrackedProductService.class);
    private final ExchangeRateRepository exchangeRates = mock(ExchangeRateRepository.class);
    private final MarketplaceRegistry marketplaces = mock(MarketplaceRegistry.class);
    private final ScraperRateLimiter rateLimiter = mock(ScraperRateLimiter.class);

    private SchedulerScraper scheduler;

    @BeforeEach
    void setUp() throws Exception {
        scheduler = new SchedulerScraper(listings, trackedProductService, exchangeRates, marketplaces, rateLimiter);
        // @Value-injected in production; set directly for a plain unit test.
        Field interval = SchedulerScraper.class.getDeclaredField("interval");
        interval.setAccessible(true);
        interval.set(scheduler, Duration.ofHours(6));
        // Consulted by the end-of-sweep breaker summary.
        when(marketplaces.allMarketplaceIds()).thenReturn(Set.of("NEWEGG", "CURRYS", "BH_PHOTO"));
    }

    private static ProductListing listing(long id, String marketplace) {
        ProductListing listing = new ProductListing();
        listing.setId(id);
        listing.setMarketplace(marketplace);
        listing.setStore(Store.NEWEGG);
        listing.setUrl("https://example.com/p/" + id);
        return listing;
    }

    @Test
    void skipsListingsOnADisabledMarketplaceButRefreshesTheRest() {
        ProductListing enabled = listing(1L, "NEWEGG");
        ProductListing disabled = listing(2L, "BH_PHOTO");
        when(listings.findByLastCheckedBeforeOrLastCheckedIsNull(any(Instant.class)))
                .thenReturn(List.of(enabled, disabled));
        when(marketplaces.isEnabled("NEWEGG")).thenReturn(true);
        when(marketplaces.isEnabled("BH_PHOTO")).thenReturn(false);

        scheduler.scrape();

        verify(trackedProductService).refreshListing(enabled);
        verify(trackedProductService, never()).refreshListing(disabled);
    }

    @Test
    void skipsListingsOnACircuitBrokenMarketplaceButRefreshesTheRest() {
        ProductListing healthy = listing(1L, "NEWEGG");
        ProductListing broken = listing(2L, "CURRYS");
        when(listings.findByLastCheckedBeforeOrLastCheckedIsNull(any(Instant.class)))
                .thenReturn(List.of(healthy, broken));
        when(marketplaces.isEnabled(anyString())).thenReturn(true);
        when(rateLimiter.isOpen("NEWEGG")).thenReturn(false);
        when(rateLimiter.isOpen("CURRYS")).thenReturn(true);
        when(rateLimiter.openReason("CURRYS")).thenReturn("Cloudflare JS challenge — not retryable");
        when(rateLimiter.snapshot("CURRYS")).thenReturn(new ScraperRateLimiter.BreakerStatus(
                ScraperRateLimiter.BreakerState.OPEN, Instant.now(),
                Instant.now().plus(Duration.ofHours(6)), "Cloudflare JS challenge — not retryable"));

        scheduler.scrape();

        verify(trackedProductService).refreshListing(healthy);
        verify(trackedProductService, never()).refreshListing(broken);
    }
}
