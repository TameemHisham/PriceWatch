package com.tameem.pricewatch.service;


import com.tameem.pricewatch.dto.PricePointResponse;
import com.tameem.pricewatch.entity.PricePoint;
import com.tameem.pricewatch.entity.ProductListing;
import com.tameem.pricewatch.entity.TrackedProduct;
import com.tameem.pricewatch.repositories.PricePointRepository;
import com.tameem.pricewatch.repositories.ProductListingRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Service
public class PriceHistoryService {

    private final PricePointRepository pricePointRepository;
    private final ProductListingRepository productListingRepository;
    public PriceHistoryService(PricePointRepository pricePointRepository, ProductListingRepository productListingRepository) {
        this.pricePointRepository = pricePointRepository;
        this.productListingRepository = productListingRepository;
    }
    public List<PricePoint> getProductListingHistory(ProductListing productListing) {
        return this.pricePointRepository.findByProductListingOrderByCheckedAtAsc(productListing);
    }
    public PricePointResponse toPricePointResponse(PricePoint pricePoint) {
        return new PricePointResponse(
                pricePoint.getCheckedAt(),
                pricePoint.getPrice(),
                pricePoint.getCurrency()
        );
    }
    public LinkedHashMap<String, List<PricePointResponse>> getProductHistory(TrackedProduct product) {
        LinkedHashMap<String, List<PricePointResponse>> history = new LinkedHashMap<>();
        List<ProductListing> listings = this.productListingRepository.findByTrackedProduct(product);
        for (ProductListing listing : listings) {
            List<PricePointResponse> pricePoint = this.getProductListingHistory(listing).stream().map(this::toPricePointResponse).toList();
            history.put(listing.getMarketplace(),pricePoint);
        }
        return history;
    }
}
