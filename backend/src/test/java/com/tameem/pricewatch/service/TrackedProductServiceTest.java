package com.tameem.pricewatch.service;

import com.tameem.pricewatch.config.MarketplaceRegistry;
import com.tameem.pricewatch.config.ScraperRegistry;
import com.tameem.pricewatch.dto.TrackResult;
import com.tameem.pricewatch.entity.ProductListing;
import com.tameem.pricewatch.entity.Store;
import com.tameem.pricewatch.entity.TrackedProduct;
import com.tameem.pricewatch.matching.CrossStoreDiscovery;
import com.tameem.pricewatch.repositories.PricePointRepository;
import com.tameem.pricewatch.repositories.ProductListingRepository;
import com.tameem.pricewatch.repositories.TrackedProductRepository;
import com.tameem.pricewatch.repositories.UserRepository;
import com.tameem.pricewatch.scraper.Availability;
import com.tameem.pricewatch.scraper.ProductData;
import com.tameem.pricewatch.scraper.ProductScraper;
import com.tameem.pricewatch.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The duplicate-listing path through {@link TrackedProductService#trackProduct}.
 * <p>
 * The exact-URL dedupe only catches a URL stored in the identical shape. A product reached
 * earlier through sibling fan-out is stored as the bare configured host
 * (https://amazon.co.uk/dp/ASIN), so tracking the same product from a pasted browser URL
 * (https://www.amazon.co.uk/dp/ASIN) slips past it, resolves to the same product by ASIN,
 * and used to insert a second listing for a marketplace the product already held — which the
 * (tracked_product_id, marketplace) unique constraint rejected as a raw 500.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrackedProductServiceTest {

    private static final String PASTED_URL = "https://www.amazon.co.uk/dp/B0TEST12345?th=1";
    private static final String CANONICAL = "https://www.amazon.co.uk/dp/B0TEST12345";
    /** What sibling fan-out stored earlier: the configured host, with no "www.". */
    private static final String SIBLING_URL = "https://amazon.co.uk/dp/B0TEST12345";
    private static final String ASIN = "B0TEST12345";
    private static final String MARKETPLACE = "AMAZON_UK";
    private static final long USER_ID = 1L;

    @Mock private TrackedProductRepository trackedProductRepository;
    @Mock private ProductListingRepository productListingRepository;
    @Mock private PricePointRepository pricePointRepository;
    @Mock private ScraperRegistry scrapers;
    @Mock private CrossStoreDiscovery discovery;
    @Mock private MarketplaceRegistry marketplaces;
    @Mock private ExchangeRateService exchangeRateService;
    @Mock private CurrentUserProvider currentUserProvider;
    @Mock private UserRepository userRepository;
    @Mock private ProductScraper scraper;

    private TrackedProductService service;
    private TrackedProduct existingProduct;
    private ProductListing existingListing;

    @BeforeEach
    void setUp() {
        service = new TrackedProductService(trackedProductRepository, productListingRepository,
                pricePointRepository, scrapers, discovery, marketplaces, exchangeRateService,
                currentUserProvider, userRepository);

        existingProduct = new TrackedProduct();
        existingProduct.setId(44L);
        existingProduct.setName("Sony WH-1000XM6 Wireless Headphones");

        existingListing = new ProductListing();
        existingListing.setId(101L);
        existingListing.setTrackedProduct(existingProduct);
        existingListing.setMarketplace(MARKETPLACE);
        existingListing.setStore(Store.AMAZON);
        existingListing.setUrl(SIBLING_URL);
        existingListing.setCurrency("GBP");

        ProductData scraped = new ProductData("Sony WH-1000XM6 Wireless Headphones",
                new BigDecimal("399.00"), "GBP", null, Availability.AVAILABLE);

        when(currentUserProvider.getCurrentUserId()).thenReturn(USER_ID);
        when(scrapers.forUrl(anyString())).thenReturn(scraper);
        when(scraper.productKey(anyString())).thenReturn(Optional.of(ASIN));
        when(scraper.canonicalUrl(PASTED_URL)).thenReturn(CANONICAL);
        when(scraper.scrape(anyString())).thenReturn(scraped);
        when(marketplaces.idFor(CANONICAL)).thenReturn(MARKETPLACE);
        when(exchangeRateService.currentRatesByCurrency()).thenReturn(Map.of());

        // The defect's precondition: the stored URL differs in shape, so this misses.
        when(productListingRepository.findByUrlAndTrackedProduct_User_Id(CANONICAL, USER_ID))
                .thenReturn(Optional.empty());
        // ...but the ASIN resolves to the product that already holds this marketplace.
        when(productListingRepository
                .findByUrlContainingAndTrackedProduct_User_Id(ASIN, USER_ID))
                .thenReturn(Optional.of(existingListing));
    }

    @Test
    void returnsTheExistingProductWithoutInsertingWhenTheMarketplaceIsAlreadyListed() {
        when(productListingRepository
                .findByTrackedProductAndMarketplace(existingProduct, MARKETPLACE))
                .thenReturn(Optional.of(existingListing));

        TrackResult result = assertDoesNotThrow(() -> service.trackProduct(PASTED_URL));

        assertFalse(result.created(), "nothing was created; this product was already tracked");
        assertEquals(44L, result.product().id());
        verify(productListingRepository, never()).save(any(ProductListing.class));
        verify(trackedProductRepository, never()).save(any(TrackedProduct.class));
    }

    /**
     * The same ASIN match, but on a marketplace the product does not hold yet: that is a
     * genuine new listing and must still be inserted, so the guard cannot simply skip every
     * ASIN-matched track.
     */
    @Test
    void stillAttachesWhenTheMatchedProductHasNoListingOnThatMarketplace() {
        when(productListingRepository
                .findByTrackedProductAndMarketplace(existingProduct, MARKETPLACE))
                .thenReturn(Optional.empty());
        when(productListingRepository.save(any(ProductListing.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(marketplaces.allMarketplaceIds()).thenReturn(java.util.Set.of(MARKETPLACE));
        when(discovery.findMatches(anyString(), any())).thenReturn(Map.of());
        when(scrapers.forMarketplace(MARKETPLACE)).thenReturn(scraper);
        when(scraper.store()).thenReturn(Store.AMAZON);

        TrackResult result = service.trackProduct(PASTED_URL);

        assertTrue(result.created());
        verify(productListingRepository).save(any(ProductListing.class));
    }
}
