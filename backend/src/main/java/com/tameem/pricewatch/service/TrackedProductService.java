package com.tameem.pricewatch.service;

import com.tameem.pricewatch.dto.*;
import com.tameem.pricewatch.entity.*;
import com.tameem.pricewatch.repositories.ExchangeRateRepository;
import com.tameem.pricewatch.repositories.PricePointRepository;
import com.tameem.pricewatch.repositories.ProductListingRepository;
import com.tameem.pricewatch.repositories.TrackedProductRepository;
import com.tameem.pricewatch.config.MarketplaceRegistry;
import com.tameem.pricewatch.scraper.ProductData;
import com.tameem.pricewatch.scraper.ProductScraper;
import com.tameem.pricewatch.scraper.ScrapeException;
import com.tameem.pricewatch.scraper.AmazonScraper;
//import jakarta.transaction.Transactional;
import org.springframework.beans.factory.ListableBeanFactory;
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
import org.springframework.web.bind.annotation.GetMapping;

@Service
public class TrackedProductService {
    private final AmazonScraper amazonScraper;
    private final TrackedProductRepository trackedProductRepository;
    private final ProductListingRepository productListingRepository;
    private final PricePointRepository pricePointRepository;
    private final ProductScraper productScraper;
    private final MarketplaceRegistry marketplaces;
    private static final Logger log = LoggerFactory.getLogger(TrackedProductService.class);
    private final ExchangeRateRepository exchangeRateRepository;
    public TrackedProductService(AmazonScraper amazonScraper, TrackedProductRepository trackedProductRepository,
                                 ProductListingRepository productListingRepository,
                                 PricePointRepository pricePointRepository,
                                 ProductScraper productScraper,
                                 MarketplaceRegistry marketplaces,ExchangeRateRepository exchangeRateRepository) {
        this.amazonScraper = amazonScraper;
        this.trackedProductRepository = trackedProductRepository;
        this.productListingRepository = productListingRepository;
        this.pricePointRepository = pricePointRepository;
        this.productScraper = productScraper;
        this.marketplaces = marketplaces;
        this.exchangeRateRepository = exchangeRateRepository;
    }

    /** Builds a canonical https://{host}/dp/{ASIN} key from a product URL, so the same
     * product always normalizes to one string regardless of path shape or query params. */
    private String normalizeUrl(String url) throws IllegalURLFormat {
        try {
            Optional<String> asin = amazonScraper.productKey(url);
            if (asin.isEmpty()) {
                throw new IllegalURLFormat("Untrackable because ASIN wasn't scraped");
            }
            URI uri = new URI(url);
            String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
            return uri.getScheme() + "://" + host +"/dp/" + asin.get();
        } catch (URISyntaxException e) {
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

        Optional<ProductListing> existingURL = productListingRepository.findByUrl(normalized);
        if (existingURL.isPresent()) {
            return new TrackResult(this.toResponse(existingURL.get().getTrackedProduct()), false);
        }

        ProductData productData = productScraper.scrape(url);
        if (productData.title() == null || productData.title().isBlank()) {
            throw new ScrapeException("Could not locate product title for URL: " + url);
        }

        Optional<String> ASIN = amazonScraper.productKey(normalized);
        Optional<ProductListing> existingASIN = ASIN.isPresent()
                ? productListingRepository.findByUrlContaining(ASIN.get())
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
            savedProduct = trackedProductRepository.save(product);
        }

        // Save the originally-requested listing first, using data already scraped.
        saveListing(savedProduct, normalized, productData, marketplaces.idFor(normalized));

        // Fan out to sibling marketplaces of the same store, using the same ASIN.
        if (ASIN.isPresent()) {
            String originalMarketplace = marketplaces.idFor(normalized);
            for (String marketplaceId : marketplaces.allMarketplaceIds()) {
                if (marketplaceId.equals(originalMarketplace)) continue;

                String siblingUrl = "https://" + marketplaces.configFor(marketplaceId).getHost()
                        + "/dp/" + ASIN.get();

                try {
                    Thread.sleep(2000);
                    ProductData siblingData = productScraper.scrape(siblingUrl);
                    if (siblingData.title() == null || siblingData.title().isBlank()) {
                        throw new ScrapeException("Could not locate product title for URL: " + siblingUrl);
                    }
                    saveListing(savedProduct, siblingUrl, siblingData, marketplaceId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ScrapeException e) {
                    log.warn("Sibling scrape failed for {} ({}): {}", marketplaceId, siblingUrl, e.toString());
                } catch (Exception e) {
                    log.error("Sibling scrape failed for {} ({}): {}", marketplaceId, siblingUrl, e.toString());
                }
            }
        }

        return new TrackResult(this.toResponse(savedProduct), true);
    }

    /** Saves one listing + its initial price point (if any) for an already-scraped marketplace. */
    private void saveListing(TrackedProduct product, String url, ProductData productData, String marketplaceId) {
        ProductListing listing = new ProductListing();
        listing.setTrackedProduct(product);
        listing.setStore(Store.AMAZON);
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
        List<TrackedProductResponse> products  = new ArrayList<>();
        for (TrackedProduct product : trackedProductRepository.findAll()) {
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
    private TrackedProduct getEntity(long id) {
        return trackedProductRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("No product with id: " + id));
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
        return this.toDetailResponse(product);
    }
    private BigDecimal convertToUsd(BigDecimal price, String currency, Map<String, BigDecimal> rates) {
        if (price == null || currency == null) return null;
        BigDecimal exchangeRate = rates.get(currency);
        if (exchangeRate == null) return null;
        return price.divide(exchangeRate, 2, RoundingMode.HALF_UP);
    }

    /** Builds the ASIN → USD rate lookup once per call, so the comparison loop below doesn't hit the DB per listing. */
    private Map<String, BigDecimal> currentRatesByCurrency() {
        return exchangeRateRepository.findAll().stream()
                .collect(Collectors.toMap(ExchangeRate::getCurrency, ExchangeRate::getExchangeRate));
    }

    /** Builds the card DTO, deriving the lowest current price and its currency across the product's listings. */
    private TrackedProductResponse toResponse(TrackedProduct product) {
        List<ProductListing> listings = productListingRepository.findByTrackedProduct(product);
        Map<String, BigDecimal> rates = currentRatesByCurrency();

        String currency = null;
        BigDecimal lowestPrice = null;
        BigDecimal lowestPriceUsd = null;

        for (ProductListing listing : listings) {
            PricePoint latest = pricePointRepository.findTopByProductListingOrderByCheckedAtDesc(listing);
            if (latest == null) continue;

            BigDecimal priceUsd = convertToUsd(latest.getPrice(), listing.getCurrency(), rates);
            if (priceUsd == null) continue; // can't compare fairly without a known rate

            if (lowestPriceUsd == null || priceUsd.compareTo(lowestPriceUsd) < 0) {
                lowestPriceUsd = priceUsd;
                lowestPrice = latest.getPrice();
                currency = listing.getCurrency();
            }
        }
        return new TrackedProductResponse(
                product.getId(), product.getName(), product.getBrand(), product.getCategory(),
                product.getTargetPrice(), product.getCreatedAt(), product.getImageUrl(),
                currency, lowestPrice, listings.size());
    }

    public TrackedProductDetailResponse toDetailResponse(TrackedProduct product) {
        List<ProductListing> listings = productListingRepository.findByTrackedProduct(product);
        Map<String, BigDecimal> rates = currentRatesByCurrency();

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
                    listing.getMarketplace()
            ));
            if (latest == null) continue;

            BigDecimal priceUsd = convertToUsd(latest.getPrice(), listing.getCurrency(), rates);
            if (priceUsd == null) continue;

            if (lowestPriceUsd == null || priceUsd.compareTo(lowestPriceUsd) < 0) {
                lowestPriceUsd = priceUsd;
                lowestPrice = latest.getPrice();
                currency = listing.getCurrency();
            }
        }
        return new TrackedProductDetailResponse(
                product.getId(), product.getName(), product.getBrand(), product.getCategory(),
                product.getTargetPrice(), product.getCreatedAt(), product.getImageUrl(),
                currency, lowestPrice, listings.size(), listingResponse);
    }

    @Transactional
    public void refreshListing(ProductListing listing) {
        ProductData productData = productScraper.scrape(listing.getUrl());
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

    public List<CurrencyResponse> getCurrentExchangeRate() {
        List<ExchangeRate> currencies =  this.exchangeRateRepository.findAll();
        return currencies.stream().map(c -> new CurrencyResponse(c.getCurrency(), c.getExchangeRate())).toList() ;
    }


}

