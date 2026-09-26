package com.tameem.pricewatch.service;

import com.tameem.pricewatch.config.MarketplaceRegistry;
import com.tameem.pricewatch.config.ScraperExecutorConfig;
import com.tameem.pricewatch.config.ScrapeProperties;
import com.tameem.pricewatch.config.ScraperRegistry;
import com.tameem.pricewatch.dto.TrackResult;
import com.tameem.pricewatch.dto.TrackedProductDetailResponse;
import com.tameem.pricewatch.entity.ProductListing;
import com.tameem.pricewatch.entity.Store;
import com.tameem.pricewatch.entity.TrackedProduct;
import com.tameem.pricewatch.entity.User;
import com.tameem.pricewatch.matching.CrossStoreDiscovery;
import com.tameem.pricewatch.repositories.PricePointRepository;
import com.tameem.pricewatch.repositories.ProductListingRepository;
import com.tameem.pricewatch.repositories.TrackedProductRepository;
import com.tameem.pricewatch.repositories.UserRepository;
import com.tameem.pricewatch.scraper.Availability;
import com.tameem.pricewatch.scraper.ProductData;
import com.tameem.pricewatch.scraper.ProductScraper;
import com.tameem.pricewatch.scraper.UnsupportedMarketplaceException;
import com.tameem.pricewatch.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;

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
    private static final long PRODUCT_ID = 44L;

    /** A sibling Amazon storefront of the same retailer as the tracked AMAZON_UK listing. */
    private static final String REGION = "AMAZON_AE";
    private static final String REGION_HOST = "amazon.ae";
    /** URL addRegion builds from ASIN + host, before normalization. */
    private static final String REGION_BUILT_URL = "https://amazon.ae/dp/B0TEST12345";
    /** What canonicalUrl must turn that into: www + https. */
    private static final String REGION_CANONICAL = "https://www.amazon.ae/dp/B0TEST12345";
    /** A marketplace of a different retailer — never a valid region for an Amazon product. */
    private static final String OTHER_RETAILER = "NEWEGG";

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
    @Mock private ExecutorService scraperExecutor;
    private TrackedProductService service;
    private TrackedProduct existingProduct;
    private ProductListing existingListing;


    @BeforeEach
    void setUp() {
        service = new TrackedProductService(trackedProductRepository, productListingRepository,
                pricePointRepository, scrapers, discovery, marketplaces, exchangeRateService,
                currentUserProvider, userRepository,scraperExecutor);

        User owner = new User();
        owner.setId(USER_ID);

        existingProduct = new TrackedProduct();
        existingProduct.setId(PRODUCT_ID);
        existingProduct.setName("Sony WH-1000XM6 Wireless Headphones");
        existingProduct.setUser(owner);

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

        // The addRegion path loads the product and the owner directly.
        when(trackedProductRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(existingProduct));
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(existingProduct.getUser()));
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

    /**
     * The sibling fan-out is gone: tracking a URL scrapes only the requested storefront, even
     * when the retailer has other configured storefronts that would once have been fetched too.
     */
    @Test
    void trackProductDoesNotFanOutToSiblingMarketplaces() {
        // A genuinely new track — the ASIN resolves to nothing already stored.
        when(productListingRepository
                .findByUrlContainingAndTrackedProduct_User_Id(ASIN, USER_ID))
                .thenReturn(Optional.empty());
        when(trackedProductRepository.save(any(TrackedProduct.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(productListingRepository.save(any(ProductListing.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(productListingRepository.findByTrackedProductAndMarketplace(any(), anyString()))
                .thenReturn(Optional.empty());
        when(scrapers.forMarketplace(anyString())).thenReturn(scraper);
        when(scraper.store()).thenReturn(Store.AMAZON);
        when(discovery.findMatches(anyString(), any())).thenReturn(Map.of());

        service.trackProduct(PASTED_URL);

        // Exactly one scrape: the requested URL. No sibling /dp/ASIN fetch on any storefront.
        verify(scraper, times(1)).scrape(anyString());
        // And no marketplace enumeration — the fan-out was its only caller in this path.
        verify(marketplaces, never()).allMarketplaceIds();
    }

    @Test
    void addRegionReturns404WhenProductBelongsToAnotherUser() {
        User someoneElse = new User();
        someoneElse.setId(999L);
        existingProduct.setUser(someoneElse);

        assertThrows(ResourceNotFoundException.class,
                () -> service.addRegion(PRODUCT_ID, REGION));
        verify(productListingRepository, never()).save(any(ProductListing.class));
    }

    @Test
    void addRegionReturns400WhenMarketplaceIsADifferentRetailer() {
        when(marketplaces.allMarketplaceIds())
                .thenReturn(Set.of(MARKETPLACE, REGION, OTHER_RETAILER));
        when(productListingRepository.findByTrackedProduct(existingProduct))
                .thenReturn(List.of(existingListing));
        when(scrapers.sameRetailer(MARKETPLACE, OTHER_RETAILER)).thenReturn(false);

        assertThrows(UnsupportedMarketplaceException.class,
                () -> service.addRegion(PRODUCT_ID, OTHER_RETAILER));
        verify(productListingRepository, never()).save(any(ProductListing.class));
    }

    @Test
    void addRegionIsIdempotentAndDoesNotInsertWhenRegionAlreadyTracked() {
        ProductListing aeListing = new ProductListing();
        aeListing.setId(202L);
        aeListing.setMarketplace(REGION);

        when(marketplaces.allMarketplaceIds()).thenReturn(Set.of(MARKETPLACE, REGION));
        when(productListingRepository.findByTrackedProduct(existingProduct))
                .thenReturn(List.of(existingListing));
        when(scrapers.sameRetailer(MARKETPLACE, REGION)).thenReturn(true);
        when(productListingRepository.findByTrackedProductAndMarketplace(existingProduct, REGION))
                .thenReturn(Optional.of(aeListing));

        TrackedProductService.RegionResult result = service.addRegion(PRODUCT_ID, REGION);

        assertFalse(result.created(), "already tracked — nothing created");
        assertEquals(202L, result.listing().getId());
        verify(productListingRepository, never()).save(any(ProductListing.class));
        verify(scraper, never()).scrape(anyString());
    }

    @Test
    void addRegionScrapesAndSavesANewRegionThroughCanonicalUrl() {
        ScrapeProperties.MarketplaceConfig aeConfig = new ScrapeProperties.MarketplaceConfig();
        aeConfig.setHost(REGION_HOST);

        when(marketplaces.allMarketplaceIds()).thenReturn(Set.of(MARKETPLACE, REGION));
        when(productListingRepository.findByTrackedProduct(existingProduct))
                .thenReturn(List.of(existingListing));
        when(scrapers.sameRetailer(MARKETPLACE, REGION)).thenReturn(true);
        when(productListingRepository.findByTrackedProductAndMarketplace(existingProduct, REGION))
                .thenReturn(Optional.empty());
        when(scrapers.forMarketplace(MARKETPLACE)).thenReturn(scraper);
        when(scrapers.forMarketplace(REGION)).thenReturn(scraper);
        when(scraper.store()).thenReturn(Store.AMAZON);
        when(scraper.productKey(SIBLING_URL)).thenReturn(Optional.of(ASIN));
        when(marketplaces.configFor(REGION)).thenReturn(aeConfig);
        // Built from ASIN + host as bare https://amazon.ae/dp/ASIN; normalization adds www.
        when(scraper.canonicalUrl(REGION_BUILT_URL)).thenReturn(REGION_CANONICAL);
        when(productListingRepository.save(any(ProductListing.class)))
                .thenAnswer(call -> call.getArgument(0));

        TrackedProductService.RegionResult result = service.addRegion(PRODUCT_ID, REGION);

        assertTrue(result.created(), "a storefront the product did not hold was added");

        ArgumentCaptor<ProductListing> saved = ArgumentCaptor.forClass(ProductListing.class);
        verify(productListingRepository).save(saved.capture());
        assertEquals(REGION_CANONICAL, saved.getValue().getUrl(),
                "the write path must store the canonicalised (www + https) URL");
        assertEquals(REGION, saved.getValue().getMarketplace());
    }

    @Test
    void availableRegionsAreSameRetailerStorefrontsMinusTrackedOnes() {
        // The product holds only AMAZON_UK. The two other Amazon storefronts are siblings;
        // NEWEGG is a different retailer and must never appear.
        when(productListingRepository.findByTrackedProduct(existingProduct))
                .thenReturn(List.of(existingListing));
        when(pricePointRepository.findByProductListingOrderByCheckedAtAsc(any()))
                .thenReturn(List.of());
        when(marketplaces.allMarketplaceIds())
                .thenReturn(Set.of(MARKETPLACE, "AMAZON_AE", "AMAZON_US", OTHER_RETAILER));
        when(scrapers.sameRetailer(MARKETPLACE, "AMAZON_AE")).thenReturn(true);
        when(scrapers.sameRetailer(MARKETPLACE, "AMAZON_US")).thenReturn(true);
        when(scrapers.sameRetailer(MARKETPLACE, OTHER_RETAILER)).thenReturn(false);

        TrackedProductDetailResponse response = service.toDetailResponse(existingProduct);

        assertEquals(List.of("AMAZON_AE", "AMAZON_US"), response.availableRegions());
    }
}
