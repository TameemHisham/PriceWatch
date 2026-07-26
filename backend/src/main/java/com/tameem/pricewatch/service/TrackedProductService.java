package com.tameem.pricewatch.service;

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
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

    @Transactional
    public TrackedProductResponse trackProduct(String url) {
        ProductData productData = productScraper.scrape(url);

        TrackedProduct product = new TrackedProduct();
        product.setName(productData.title());
        product.setImageUrl(productData.imageUrl());
        TrackedProduct savedProduct = trackedProductRepository.save(product);

        ProductListing listing = new ProductListing();
        listing.setTrackedProduct(savedProduct);
        listing.setStore(Store.AMAZON);
        listing.setUrl(url);
        listing.setCurrency(productData.currency());
        ProductListing savedListing = productListingRepository.save(listing);

        PricePoint pricePoint = new PricePoint();
        pricePoint.setProductListing(savedListing);
        pricePoint.setPrice(productData.price());
        pricePoint.setCheckedAt(Instant.now());
        pricePointRepository.save(pricePoint);
        return this.toResponse(savedProduct);
    }
    public List<TrackedProductResponse> getAllProducts() {
        List<TrackedProductResponse> products  = new ArrayList<TrackedProductResponse>();
        for (TrackedProduct product : trackedProductRepository.findAll()) {
            products.add(this.toResponse(product));
        }
        return products;
    }
    public TrackedProductResponse getProduct(long id) {
        return this.toResponse(this.getEntity(id));
    }
    private TrackedProduct getEntity(long id) {
        return trackedProductRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("No product with id: " + id));
    }
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

}

