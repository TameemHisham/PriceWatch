package com.tameem.pricewatch.service;

import com.tameem.pricewatch.dto.ListingResponse;
import com.tameem.pricewatch.dto.TrackResult;
import com.tameem.pricewatch.dto.TrackedProductDetailResponse;
import com.tameem.pricewatch.dto.TrackedProductResponse;
import com.tameem.pricewatch.entity.PricePoint;
import com.tameem.pricewatch.entity.ProductListing;
import com.tameem.pricewatch.entity.Store;
import com.tameem.pricewatch.entity.TrackedProduct;
import com.tameem.pricewatch.repositories.PricePointRepository;
import com.tameem.pricewatch.repositories.ProductListingRepository;
import com.tameem.pricewatch.repositories.TrackedProductRepository;
import com.tameem.pricewatch.scraper.ProductData;
import com.tameem.pricewatch.scraper.ProductScraper;
import com.tameem.pricewatch.scraper.ScrapeException;
//import jakarta.transaction.Transactional;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class TrackedProductService {

    private final TrackedProductRepository trackedProductRepository;
    private final ProductListingRepository productListingRepository;
    private final PricePointRepository pricePointRepository;
    private final ProductScraper productScraper;

    public TrackedProductService(TrackedProductRepository trackedProductRepository,
                                 ProductListingRepository productListingRepository,
                                 PricePointRepository pricePointRepository,
                                 ProductScraper productScraper) {
        this.trackedProductRepository = trackedProductRepository;
        this.productListingRepository = productListingRepository;
        this.pricePointRepository = pricePointRepository;
        this.productScraper = productScraper;
    }

    /** Strips query string, trailing slash and host casing so the same product always yields one key. */
    private String normalizeUrl(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
            String path = uri.getPath() != null ? uri.getPath() : "";
            if (path.endsWith("/") && path.length() > 1) {
                path = path.substring(0, path.length() - 1);
            }
            return uri.getScheme() + "://" + host + path; // https + :// + pricewatch.com + :8080
        } catch (URISyntaxException e) {
            return url; //  unparseable
        }

    }

    /** Tracks a URL: returns the existing product if already tracked, otherwise scrapes and creates one. */
    @Transactional
    public TrackResult trackProduct(String url) {
        String normalized = normalizeUrl(url);

        // Already tracking this listing? Return it instead of scraping and inserting again.
        Optional<ProductListing> existing = productListingRepository.findByUrl(normalized);
        if (existing.isPresent()) {
            return new TrackResult(this.toResponse(existing.get().getTrackedProduct()), false);
        }

        ProductData productData = productScraper.scrape(url);

        // name is NOT NULL in the schema — a missing title means the selectors rotted,
        // which is a scrape failure, not a database problem.
        if (productData.title() == null || productData.title().isBlank()) {
            throw new ScrapeException("Could not locate product title for URL: " + url);
        }

        TrackedProduct product = new TrackedProduct();
        product.setName(productData.title());
        product.setImageUrl(productData.imageUrl());
        TrackedProduct savedProduct = trackedProductRepository.save(product);

        Instant now = Instant.now();

        ProductListing listing = new ProductListing();
        listing.setTrackedProduct(savedProduct);
        listing.setStore(Store.AMAZON);
        listing.setUrl(normalized);
        listing.setCurrency(productData.currency());
        listing.setLastChecked(now);
        ProductListing savedListing = productListingRepository.save(listing);

        PricePoint pricePoint = new PricePoint();
        pricePoint.setProductListing(savedListing);
        pricePoint.setPrice(productData.price());
        pricePoint.setCheckedAt(now);
        pricePointRepository.save(pricePoint);

        return new TrackResult(this.toResponse(savedProduct), true);
    }
    /** Every tracked product as a dashboard card. Runs one query per product per listing (N+1, cached in Phase 6). */
    @Transactional(readOnly = true)
    public List<TrackedProductDetailResponse> getAllProducts() {
        List<TrackedProductDetailResponse> products  = new ArrayList<>();
        for (TrackedProduct product : trackedProductRepository.findAll()) {
            products.add(this.toDetailResponse(product));
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
    public TrackedProductResponse reTrack(long id) {
        TrackedProduct product = getEntity(id);
        List<ProductListing> listings = productListingRepository.findByTrackedProduct(product);
        for (ProductListing listing : listings) {
            ProductData productData = productScraper.scrape(listing.getUrl());
            PricePoint pricePoint = new PricePoint();
            pricePoint.setProductListing(listing);
            pricePoint.setPrice(productData.price());
            pricePoint.setCheckedAt(Instant.now());
            pricePointRepository.save(pricePoint);
            listing.setLastChecked(Instant.now());
            productListingRepository.save(listing);
        }
        return this.toResponse(product);
    }
    /** Builds the card DTO, deriving the lowest current price and its currency across the product's listings. */
    private TrackedProductResponse toResponse(TrackedProduct product) {
        List<ProductListing> listings = productListingRepository.findByTrackedProduct(product);
        String currency = null;
        BigDecimal lowestPrice = null;
        for (ProductListing listing : listings) {
            PricePoint latest = pricePointRepository.findTopByProductListingOrderByCheckedAtDesc(listing);
            if (latest == null) continue;
            if (lowestPrice == null || latest.getPrice().compareTo(lowestPrice) < 0) {
                lowestPrice = latest.getPrice();
                currency = listing.getCurrency();
            }
        }
        return new TrackedProductResponse(
                product.getId(), product.getName(), product.getBrand(), product.getCategory(),
                product.getTargetPrice(), product.getCreatedAt(), product.getImageUrl(),
                currency, lowestPrice, listings.size());
    }
    private TrackedProductDetailResponse toDetailResponse(TrackedProduct product) {
        List<ProductListing> listings = productListingRepository.findByTrackedProduct(product);
        String currency = null;
        BigDecimal lowestPrice = null;
        List<ListingResponse> listingResponse = new ArrayList<>();
        for (ProductListing listing : listings) {
            PricePoint latest = pricePointRepository.findTopByProductListingOrderByCheckedAtDesc(listing);
            listingResponse.add(new ListingResponse(
                            listing.getStore(),
                            listing.getUrl(),
                            listing.getCurrency(),
                    latest != null ? latest.getPrice() : null
                    ));
            if (latest == null) continue;
            if (lowestPrice == null || latest.getPrice().compareTo(lowestPrice) < 0) {
                lowestPrice = latest.getPrice();
                currency = listing.getCurrency();
            }

            }
        return new TrackedProductDetailResponse(
                product.getId(), product.getName(), product.getBrand(), product.getCategory(),
                product.getTargetPrice(), product.getCreatedAt(), product.getImageUrl(),
                currency, lowestPrice, listings.size(),listingResponse);

        }


}

