package com.tameem.pricewatch.service;

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

import java.time.LocalDateTime;
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
    public TrackedProduct trackProduct(String url) {
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
        pricePoint.setCheckedAt(LocalDateTime.now());
        pricePointRepository.save(pricePoint);

        return savedProduct;
    }
    public List<TrackedProduct> getAllProducts() {
        return trackedProductRepository.findAll();
    }
    public TrackedProduct getProduct(long id) {
        return trackedProductRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("No product with id: " + id));
    }
    @Transactional
    public void deleteProduct(long id) {
        TrackedProduct product = getProduct(id);
        List<ProductListing> listings = productListingRepository.findByTrackedProduct(product);
        for (ProductListing listing : listings) {
            pricePointRepository.deleteByProductListing(listing);
        }
        productListingRepository.deleteAll(listings);
        trackedProductRepository.deleteById(id);
    }

    @Transactional
    public TrackedProduct reTrack(long id) {
        TrackedProduct product = getProduct(id);
        List<ProductListing> listings = productListingRepository.findByTrackedProduct(product);
        for (ProductListing listing : listings) {
            ProductData productData = productScraper.scrape(listing.getUrl());
            PricePoint pricePoint = new PricePoint();
            pricePoint.setProductListing(listing);
            pricePoint.setPrice(productData.price());
            pricePoint.setCheckedAt(LocalDateTime.now());
            pricePointRepository.save(pricePoint);
            listing.setLastChecked(LocalDateTime.now());
            productListingRepository.save(listing);
        }
        return product;
    }

}

