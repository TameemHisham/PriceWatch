package com.tameem.pricewatch.service;

import com.tameem.pricewatch.dto.*;
import com.tameem.pricewatch.entity.*;
import com.tameem.pricewatch.repositories.PricePointRepository;
import com.tameem.pricewatch.repositories.ProductListingRepository;
import com.tameem.pricewatch.repositories.TrackedProductRepository;
import com.tameem.pricewatch.config.MarketplaceRegistry;
import com.tameem.pricewatch.config.ScraperRegistry;
import com.tameem.pricewatch.matching.CrossStoreDiscovery;
import com.tameem.pricewatch.scraper.SearchResult;
import com.tameem.pricewatch.repositories.UserRepository;
import com.tameem.pricewatch.scraper.ProductData;
import com.tameem.pricewatch.scraper.ProductScraper;
import com.tameem.pricewatch.scraper.ScrapeException;
import com.tameem.pricewatch.security.CurrentUserProvider;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class TrackedProductService {
    private final TrackedProductRepository trackedProductRepository;
    private final ProductListingRepository productListingRepository;
    private final ExchangeRateService exchangeRateService;
    private final PricePointRepository pricePointRepository;
    private final ScraperRegistry scrapers;
    private final CrossStoreDiscovery discovery;
    private final MarketplaceRegistry marketplaces;
    private static final Logger log = LoggerFactory.getLogger(TrackedProductService.class);
    private final CurrentUserProvider currentUserProvider;
    private final UserRepository userRepository;


    public TrackedProductService(TrackedProductRepository trackedProductRepository,
                                 ProductListingRepository productListingRepository,
                                 PricePointRepository pricePointRepository,
                                 ScraperRegistry scrapers,
                                 CrossStoreDiscovery discovery,
                                 MarketplaceRegistry marketplaces, ExchangeRateService exchangeRateService,CurrentUserProvider currentUserProvider,UserRepository userRepository) {
        this.trackedProductRepository = trackedProductRepository;
        this.productListingRepository = productListingRepository;
        this.pricePointRepository = pricePointRepository;
        this.scrapers = scrapers;
        this.discovery = discovery;
        this.marketplaces = marketplaces;
        this.exchangeRateService = exchangeRateService;
        this.currentUserProvider=currentUserProvider;
        this.userRepository = userRepository;
    }

    /** Asks the storefront's own scraper for a canonical key, so the same product always
     * normalizes to one string regardless of path shape or query params. Amazon still yields
     * https://{host}/dp/{ASIN}; each other store defines its own equivalent. */
    private String normalizeUrl(String url) throws IllegalURLFormat {
        ProductScraper scraper = scrapers.forUrl(url);
        if (scraper.productKey(url).isEmpty()) {
            throw new IllegalURLFormat("Untrackable because no product id was found in the URL");
        }
        try {
            return scraper.canonicalUrl(url);
        } catch (ScrapeException e) {
            throw new IllegalURLFormat("unparseable");
        }
    }
//    Jaccard similarity
    private static final double TITLE_SIMILARITY_THRESHOLD = 0.4;

    private double titleSimilarity(String a, String b) {
        if (a == null || b == null) return 0.0;

        Set<String> tokensA = tokenize(a);
        Set<String> tokensB = tokenize(b);

        Set<String> intersection = new HashSet<>(tokensA);
        intersection.retainAll(tokensB);

        Set<String> union = new HashSet<>(tokensA);
        union.addAll(tokensB);

        if (union.isEmpty()) return 0.0;

        return (double) intersection.size() / union.size();
    }

    private Set<String> tokenize(String title) {
        return Arrays.stream(title.toLowerCase().split("[^a-z0-9]+"))
                .filter(token -> !token.isBlank())
                .collect(Collectors.toSet());
    }
    /** Tracks a URL: returns the existing product if already tracked, otherwise scrapes and creates one. */
    @Transactional
    public TrackResult trackProduct(String url) {
        String normalized = normalizeUrl(url);
        Long userId = currentUserProvider.getCurrentUserId();

        // Both dedupe lookups are scoped to the caller. Unscoped, they matched any user's
        // listing: the URL check handed back another user's product (which the owner check
        // on the detail endpoint then refused), and the id check could attach this listing
        // to their TrackedProduct, feeding our price points into their history.
        Optional<ProductListing> existingURL =
                productListingRepository.findByUrlAndTrackedProduct_User_Id(normalized, userId);
        if (existingURL.isPresent()) {
            return new TrackResult(this.toResponse(existingURL.get().getTrackedProduct()), false);
        }

        ProductData productData = scrapers.forUrl(url).scrape(url);
        if (productData.title() == null || productData.title().isBlank()) {
            throw new ScrapeException("Could not locate product title for URL: " + url);
        }

        Optional<String> ASIN = scrapers.forUrl(normalized).productKey(normalized);
        Optional<ProductListing> existingASIN = ASIN.isPresent()
                ? productListingRepository.findByUrlContainingAndTrackedProduct_User_Id(
                        ASIN.get(), userId)
                : Optional.empty();

        TrackedProduct savedProduct;
        boolean matched = false;

        if (existingASIN.isPresent()) {
            TrackedProduct candidate = existingASIN.get().getTrackedProduct();
            double similarity = titleSimilarity(productData.title(), candidate.getName());
            if (similarity >= TITLE_SIMILARITY_THRESHOLD) {
                savedProduct = candidate;
                matched = true;
            } else {
                log.warn("ASIN match rejected (similarity {}) for '{}' vs existing '{}'",
                        similarity, productData.title(), candidate.getName());
                savedProduct = null;
            }
        } else {
            savedProduct = null;
        }

        if (!matched) {
            TrackedProduct product = new TrackedProduct();
            product.setName(productData.title());
            product.setImageUrl(productData.imageUrl());
            User currentUser = userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
            product.setUser(currentUser); // Assign to user
            savedProduct = trackedProductRepository.save(product);
        }

        // Save the originally-requested listing first, using data already scraped.
        saveListing(savedProduct, normalized, productData, marketplaces.idFor(normalized),
                ListingOrigin.USER_SUBMITTED);

        // Fan out to sibling marketplaces of the same store, using the same ASIN.
        if (ASIN.isPresent()) {
            String originalMarketplace = marketplaces.idFor(normalized);
            for (String marketplaceId : marketplaces.allMarketplaceIds()) {
                if (marketplaceId.equals(originalMarketplace)) continue;
                // A product id only carries across storefronts of one retailer: an ASIN means
                // something on amazon.co.uk/.ae/.com and nothing on any other store, so fanning
                // out to every configured marketplace would build junk URLs.
                if (!scrapers.sameRetailer(originalMarketplace, marketplaceId)) continue;

                String siblingUrl = "https://" + marketplaces.configFor(marketplaceId).getHost()
                        + "/dp/" + ASIN.get();

                try {
                    Thread.sleep(2000);
                    ProductData siblingData = scrapers.forUrl(siblingUrl).scrape(siblingUrl);
                    if (siblingData.title() == null || siblingData.title().isBlank()) {
                        throw new ScrapeException("Could not locate product title for URL: " + siblingUrl);
                    }
                    saveListing(savedProduct, siblingUrl, siblingData, marketplaceId,
                            ListingOrigin.SIBLING_MARKETPLACE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ScrapeException e) {
                    log.warn("Sibling scrape failed for {} ({}): {}", marketplaceId, siblingUrl, e.toString());
                } catch (Exception e) {
                    log.error("Sibling scrape failed for {} ({}): {}", marketplaceId, siblingUrl, e.toString());
                }
            }
        }

        // Look for the same product on other storefronts. Additive: the requested URL has
        // already been scraped and saved above, and nothing here can change that listing.
        attachDiscoveredListings(savedProduct, productData.title());

        return new TrackResult(this.toResponse(savedProduct), true);
    }

    /**
     * Tracks by product name instead of URL: searches the storefronts that can be searched,
     * keeps only hits that clear the attribute gate, and builds a product from them.
     */
    @Transactional
    public TrackResult trackProductByName(String name) {
        Map<String, SearchResult> matches = discovery.findMatches(name);
        if (matches.isEmpty()) {
            throw new ResourceNotFoundException(
                    "No matching product found on any searchable store for: " + name);
        }

        TrackedProduct product = null;
        for (Map.Entry<String, SearchResult> entry : matches.entrySet()) {
            SearchResult hit = entry.getValue();
            try {
                ProductScraper scraper = scrapers.forUrl(hit.url());
                ProductData data = scraper.scrape(hit.url());
                if (data.title() == null || data.title().isBlank()) continue;

                if (product == null) {
                    // The first hit that actually scrapes becomes the product itself.
                    TrackedProduct fresh = new TrackedProduct();
                    fresh.setName(data.title());
                    fresh.setImageUrl(data.imageUrl());
                    fresh.setUser(userRepository.findById(currentUserProvider.getCurrentUserId())
                            .orElseThrow(() -> new IllegalStateException("Authenticated user not found")));
                    product = trackedProductRepository.save(fresh);
                }
                saveListing(product, scraper.canonicalUrl(hit.url()), data, entry.getKey(),
                        ListingOrigin.CROSS_STORE_DISCOVERY);
            } catch (RuntimeException e) {
                log.warn("Discovered listing failed to scrape for {} ({}): {}",
                        entry.getKey(), hit.url(), e.toString());
            }
        }

        if (product == null) {
            throw new ResourceNotFoundException(
                    "Matches were found but none could be scraped for: " + name);
        }
        return new TrackResult(this.toResponse(product), true);
    }

    /**
     * Attaches listings for the same product found on other storefronts. Best effort by
     * design — a store being unsearchable, unreachable or unmatched leaves the tracked
     * product exactly as it already was.
     */
    private void attachDiscoveredListings(TrackedProduct product, String title) {
        attachMatches(product, discovery.findMatches(title, attachedMarketplaces(product)));
    }

    /** Marketplace ids this product already holds a listing for. */
    private List<String> attachedMarketplaces(TrackedProduct product) {
        return productListingRepository.findByTrackedProduct(product).stream()
                .map(ProductListing::getMarketplace)
                .toList();
    }

    /** Scrapes and saves each confirmed match; returns the marketplaces actually attached. */
    private List<String> attachMatches(TrackedProduct product, Map<String, SearchResult> matches) {
        List<String> attached = new ArrayList<>();
        for (Map.Entry<String, SearchResult> entry : matches.entrySet()) {
            SearchResult hit = entry.getValue();
            try {
                ProductScraper scraper = scrapers.forUrl(hit.url());
                ProductData data = scraper.scrape(hit.url());
                if (data.title() == null || data.title().isBlank()) continue;
                saveListing(product, scraper.canonicalUrl(hit.url()), data, entry.getKey(),
                        ListingOrigin.CROSS_STORE_DISCOVERY);
                log.info("Attached discovered listing on {} to product {}",
                        entry.getKey(), product.getId());
                attached.add(entry.getKey());
            } catch (RuntimeException e) {
                log.warn("Discovered listing failed to scrape for {} ({}): {}",
                        entry.getKey(), hit.url(), e.toString());
            }
        }
        return attached;
    }

    /**
     * One product's discovery pass, as its own transaction — the unit the one-time backfill
     * loops over. Products tracked before a storefront became searchable never had
     * discovery run against them, and neither entry point that runs it (initial track,
     * manual refresh) fires retroactively.
     * <p>
     * Deliberately not user-scoped: this is maintenance over the whole table, invoked from
     * a runner rather than a request, so there is no caller to scope to.
     *
     * @param dryRun when true, reports what would be attached and writes nothing
     * @return the marketplaces attached, or that would be
     */
    @Transactional
    public List<String> backfillDiscovery(long productId, boolean dryRun) {
        TrackedProduct product = trackedProductRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("No product with id: " + productId));
        Map<String, SearchResult> matches =
                discovery.findMatches(product.getName(), attachedMarketplaces(product));
        if (dryRun) {
            return List.copyOf(matches.keySet());
        }
        return attachMatches(product, matches);
    }

    /** Saves one listing + its initial price point (if any) for an already-scraped marketplace. */
    private void saveListing(TrackedProduct product, String url, ProductData productData,
                             String marketplaceId, ListingOrigin origin) {
        ProductListing listing = new ProductListing();
        listing.setTrackedProduct(product);
        listing.setOrigin(origin);
        listing.setStore(scrapers.forMarketplace(marketplaceId).store());
        listing.setUrl(url);
        listing.setMarketplace(marketplaceId);
        listing.setCurrency(productData.currency() == null ? "UNKNOWN" : productData.currency());
        listing.setLastChecked(Instant.now());
        ProductListing savedListing = productListingRepository.save(listing);

        if (productData.hasPrice()) {
            PricePoint pricePoint = new PricePoint();
            pricePoint.setProductListing(savedListing);
            pricePoint.setPrice(productData.price());
            pricePoint.setCurrency(productData.currency());
            pricePoint.setCheckedAt(Instant.now());
            pricePointRepository.save(pricePoint);
        } else {
            log.info("Tracking {} with no current offer — no initial price point", url);
        }
    }
    /** Every tracked product as a dashboard card. Runs one query per product per listing (N+1, cached in Phase 6). */
    @Transactional(readOnly = true)
    public List<TrackedProductResponse> getAllProducts() {
        Long userId = currentUserProvider.getCurrentUserId();
        List<TrackedProductResponse> products = new ArrayList<>();
        for (TrackedProduct product : trackedProductRepository.findByUserId(userId)) {
            products.add(this.toResponse(product));
        }
        return products;
    }
    /** One product as a card DTO, or 404 if the id does not exist. */
    @Transactional(readOnly = true)
    public TrackedProductDetailResponse getProduct(long id) {
        return this.toDetailResponse(this.getEntity(id));
    }
    /** Loads the entity by id or throws ResourceNotFoundException (mapped to 404). */
    public TrackedProduct getEntity(long id) {
        TrackedProduct product = trackedProductRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No product with id: " + id));

        Long currentUserId = currentUserProvider.getCurrentUserId();
        if (product.getUser() == null || !currentUserId.equals(product.getUser().getId())) {
            throw new ResourceNotFoundException("No product with id: " + id);
        }

        return product;
    }
    /** Deletes a product and everything under it: price points first, then listings, then the product. */
    @Transactional
    public void deleteProduct(long id) {
        TrackedProduct product = getEntity(id);
        List<ProductListing> listings = productListingRepository.findByTrackedProduct(product);
        for (ProductListing listing : listings) {
            pricePointRepository.deleteByProductListing(listing);
        }
        productListingRepository.deleteAll(listings);
        trackedProductRepository.deleteById(id);
    }

    /** Re-scrapes every listing of a product and appends a new price point to each. */
    @Transactional
    public TrackedProductDetailResponse reTrack(long id) {
        TrackedProduct product = getEntity(id);
        List<ProductListing> listings = productListingRepository.findByTrackedProduct(product);
        for (ProductListing listing : listings) {
            try {
                refreshListing(listing);
            } catch (ScrapeException e) {
                log.warn("Refresh failed for listing {} ({}): {}", listing.getId(), listing.getUrl(), e.toString());
            } catch (Exception e) {
                log.error("Refresh failed for listing {} ({}): {}", listing.getId(), listing.getUrl(), e.toString());
            }
        }

        // Also look again for stores this product does not have yet. A product tracked
        // before a storefront became searchable never got the chance, and the searchable
        // set grows over time.
        //
        // Deliberately here and not in refreshListing: the scheduled sweep calls that
        // method directly, per listing, so discovery there would run for every listing of
        // every product on every sweep. reTrack is only reached from the refresh button.
        attachDiscoveredListings(product, product.getName());

        return this.toDetailResponse(product);
    }



    /** Builds the card DTO, deriving the lowest current price and its currency across the product's listings. */
    private TrackedProductResponse toResponse(TrackedProduct product) {
        List<ProductListing> listings = productListingRepository.findByTrackedProduct(product);
        Map<String, BigDecimal> rates = exchangeRateService.currentRatesByCurrency();

        String currency = null;
        BigDecimal lowestPrice = null;
        BigDecimal lowestPriceUsd = null;

        for (ProductListing listing : listings) {
            PricePoint latest = pricePointRepository.findTopByProductListingOrderByCheckedAtDesc(listing);
            if (latest == null) continue;

            BigDecimal priceUsd = exchangeRateService.convertToUsd(latest.getPrice(), listing.getCurrency(), rates, listing.getId());
            if (priceUsd == null) continue; // can't compare fairly without a known rate

            if (lowestPriceUsd == null || priceUsd.compareTo(lowestPriceUsd) < 0) {
                lowestPriceUsd = priceUsd;
                lowestPrice = latest.getPrice();
                currency = listing.getCurrency();
            }
        }
        boolean targetReached = product.getTargetPrice() != null && lowestPrice != null && lowestPrice.compareTo(product.getTargetPrice()) <= 0;
        List<BigDecimal> recentPrices = getRecentPricesUsd(product, rates);
        BigDecimal trendPercent = getTrendPercent(recentPrices);
        // Distinct retailers behind this product, so a card can name its store instead of
        // assuming Amazon. Order is stable so the label does not reshuffle between loads.
        List<String> stores = listings.stream()
                .map(listing -> listing.getStore().name())
                .distinct()
                .sorted()
                .toList();
        return new TrackedProductResponse(
                product.getId(), product.getName(), product.getBrand(), product.getCategory(),
                product.getTargetPrice(), product.getCreatedAt(), product.getImageUrl(),
                currency, lowestPrice, listings.size(),targetReached,recentPrices, trendPercent,
                stores);
    }

    public TrackedProductDetailResponse toDetailResponse(TrackedProduct product) {
        List<ProductListing> listings = productListingRepository.findByTrackedProduct(product);
        Map<String, BigDecimal> rates = exchangeRateService.currentRatesByCurrency();

        String currency = null;
        BigDecimal lowestPrice = null;
        BigDecimal lowestPriceUsd = null;
        List<ListingResponse> listingResponse = new ArrayList<>();

        for (ProductListing listing : listings) {
            PricePoint latest = pricePointRepository.findTopByProductListingOrderByCheckedAtDesc(listing);
            listingResponse.add(new ListingResponse(
                    listing.getStore(),
                    listing.getUrl(),
                    listing.getCurrency(),
                    latest != null ? latest.getPrice() : null,
                    listing.getMarketplace(),
                    listing.getOrigin()
            ));
            if (latest == null) continue;

            BigDecimal priceUsd = exchangeRateService.convertToUsd(latest.getPrice(), listing.getCurrency(), rates, listing.getId());
            if (priceUsd == null) continue;

            if (lowestPriceUsd == null || priceUsd.compareTo(lowestPriceUsd) < 0) {
                lowestPriceUsd = priceUsd;
                lowestPrice = latest.getPrice();
                currency = listing.getCurrency();
            }
        }
        boolean targetReached = product.getTargetPrice() != null && lowestPrice != null && lowestPrice.compareTo(product.getTargetPrice()) <= 0;
        return new TrackedProductDetailResponse(
                product.getId(), product.getName(), product.getBrand(), product.getCategory(),
                product.getTargetPrice(), product.getCreatedAt(), product.getImageUrl(),
                currency, lowestPrice, listings.size(), listingResponse,targetReached,this.getAllTimeLow(product, rates));
    }

    @Transactional
    public void refreshListing(ProductListing listing) {
        ProductData productData = scrapers.forUrl(listing.getUrl()).scrape(listing.getUrl());
        if (productData.title() == null || productData.title().isBlank()) {
            throw new ScrapeException("Could not locate product title for URL: " + listing.getUrl());
        }
        Instant now = Instant.now();
        // No offer at this location: record the check, record no price. This is a
        // successful observation, not a failure — the chart should show a gap
        // rather than a fabricated value.
        if (!productData.hasPrice()) {
            listing.setLastChecked(now);
            productListingRepository.save(listing);
            log.info("No offer for listing {} ({}) — recorded check, no price point",
                    listing.getId(), listing.getUrl());
            return;
        }

        // Retailers localise by visitor IP, so an observed currency can differ
        // between sweeps — and the first scrape may not have resolved one at all.
        // Refresh it every time rather than trusting the value written at track.
        // Never downgrade a known currency to the parser's UNKNOWN sentinel:
        // a single unparseable sweep would otherwise destroy a good value.
        String observed = productData.currency();
        if (observed != null && !observed.isBlank() && !"UNKNOWN".equals(observed)) {
            listing.setCurrency(observed);
        }
        listing.setLastChecked(now);
        ProductListing savedListing = productListingRepository.save(listing);

        PricePoint pricePoint = new PricePoint();
        pricePoint.setProductListing(savedListing);
        pricePoint.setPrice(productData.price());
        // Recorded as observed, even when UNKNOWN: an amount whose currency we
        // cannot name is still a fact about this moment, and labelling it with
        // the listing's last-known currency would be worse.
        pricePoint.setCurrency(productData.currency());
        pricePoint.setCheckedAt(now);
        pricePointRepository.save(pricePoint);

    }
    public void setTargetPrice(long id, BigDecimal targetPrice) {
        TrackedProduct product = getEntity(id);
        product.setTargetPrice(targetPrice);
        trackedProductRepository.save(product);
    }
    /** Lowest price ever recorded for this product, across all listings and all history ,
     * compared in USD like the current-lowest logic, but returned in its original currency. */
    private BigDecimal getAllTimeLow(TrackedProduct product, Map<String, BigDecimal> rates) {
        List<ProductListing> listings = productListingRepository.findByTrackedProduct(product);
        BigDecimal allTimeLowUsd = null;
        BigDecimal allTimeLowOriginal = null;

        for (ProductListing listing : listings) {
            for (PricePoint point : pricePointRepository.findByProductListingOrderByCheckedAtAsc(listing)) {
                BigDecimal priceUsd = exchangeRateService.convertToUsd(point.getPrice(), point.getCurrency(), rates);
                if (priceUsd == null) continue;

                if (allTimeLowUsd == null || priceUsd.compareTo(allTimeLowUsd) < 0) {
                    allTimeLowUsd = priceUsd;
                    allTimeLowOriginal = point.getPrice();
                }
            }
        }
        return allTimeLowOriginal;
    }
    /** Last N USD-converted price points across all listings, in chronological order,
     * plus the % change from the first to the last of those points — powers dashboard
     * sparklines and trend pills without a separate history call per card. */
    private static final int RECENT_POINTS_WINDOW = 10;

    private List<BigDecimal> getRecentPricesUsd(TrackedProduct product, Map<String, BigDecimal> rates) {
        List<ProductListing> listings = productListingRepository.findByTrackedProduct(product);

        List<Map.Entry<Instant, BigDecimal>> allPoints = new ArrayList<>();
        for (ProductListing listing : listings) {
            for (PricePoint point : pricePointRepository.findByProductListingOrderByCheckedAtAsc(listing)) {
                BigDecimal priceUsd = exchangeRateService.convertToUsd(point.getPrice(), point.getCurrency(), rates);
                if (priceUsd == null) continue;
                allPoints.add(Map.entry(point.getCheckedAt(), priceUsd));
            }
        }

        allPoints.sort(Map.Entry.comparingByKey());

        int fromIndex = Math.max(0, allPoints.size() - RECENT_POINTS_WINDOW);
        return allPoints.subList(fromIndex, allPoints.size()).stream()
                .map(Map.Entry::getValue)
                .toList();
    }

    private BigDecimal getTrendPercent(List<BigDecimal> recentPricesUsd) {
        if (recentPricesUsd.size() < 2) return null;

        BigDecimal first = recentPricesUsd.get(0);
        BigDecimal last = recentPricesUsd.get(recentPricesUsd.size() - 1);
        if (first.compareTo(BigDecimal.ZERO) == 0) return null;

        return last.subtract(first)
                .divide(first, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

}

