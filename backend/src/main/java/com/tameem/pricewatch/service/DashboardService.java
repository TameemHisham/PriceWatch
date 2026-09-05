package com.tameem.pricewatch.service;

import com.tameem.pricewatch.dto.DashboardSummaryResponse;
import com.tameem.pricewatch.entity.PricePoint;
import com.tameem.pricewatch.entity.ProductListing;
import com.tameem.pricewatch.entity.TrackedProduct;
import com.tameem.pricewatch.repositories.PricePointRepository;
import com.tameem.pricewatch.repositories.ProductListingRepository;
import com.tameem.pricewatch.repositories.TrackedProductRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

@Service
public class DashboardService {

    private final TrackedProductRepository trackedProductRepository;
    private final ProductListingRepository productListingRepository;
    private final PriceHistoryService priceHistoryService;
    private final ExchangeRateService exchangeRateService;
    private final PricePointRepository pricePointRepository;

    public DashboardService(TrackedProductRepository trackedProductRepository,
                            ProductListingRepository productListingRepository,
                            PriceHistoryService priceHistoryService,
                            ExchangeRateService exchangeRateService,
                            PricePointRepository pricePointRepository) {
        this.trackedProductRepository = trackedProductRepository;
        this.productListingRepository = productListingRepository;
        this.priceHistoryService = priceHistoryService;
        this.exchangeRateService = exchangeRateService;
        this.pricePointRepository = pricePointRepository;
    }

    public DashboardSummaryResponse getDashboardSummary() {
        List<TrackedProduct> products = trackedProductRepository.findAll();
        Map<String, BigDecimal> rates = exchangeRateService.currentRatesByCurrency();

        int dropCount = 0;
        BigDecimal savings = BigDecimal.ZERO;

        for (TrackedProduct product : products) {
            BigDecimal allTimeHighUsd = getHighestPriceForTrackedProduct(product, rates);
            if (allTimeHighUsd == null) continue;

            BigDecimal currentPriceUsd = getCurrentPriceUsd(product, rates);
            if (currentPriceUsd == null) continue;

            BigDecimal drop = allTimeHighUsd.subtract(currentPriceUsd);
            if (drop.compareTo(BigDecimal.ZERO) > 0) {
                dropCount++;
                savings = savings.add(drop);
            }
        }

        BigDecimal averageDrop = dropCount == 0
                ? BigDecimal.ZERO
                : savings.divide(BigDecimal.valueOf(dropCount), 2, RoundingMode.HALF_UP);

        return new DashboardSummaryResponse(
                Math.toIntExact(trackedProductRepository.count()),
                dropCount,
                savings,
                averageDrop
        );
    }

    private BigDecimal getHighestPriceForTrackedProduct(TrackedProduct product, Map<String, BigDecimal> rates) {
        List<ProductListing> listings = productListingRepository.findByTrackedProduct(product);
        BigDecimal allTimeHighUsd = null;
        for (ProductListing listing : listings) {
            for (PricePoint point : priceHistoryService.getProductListingHistory(listing)) {
                BigDecimal priceUsd = exchangeRateService.convertToUsd(point.getPrice(), point.getCurrency(), rates);
                if (priceUsd == null) continue;
                if (allTimeHighUsd == null || priceUsd.compareTo(allTimeHighUsd) > 0) {
                    allTimeHighUsd = priceUsd;
                }
            }
        }
        return allTimeHighUsd;
    }

    /** Lowest current USD price across the product's listings — same "lowest across stores" rule as toResponse/toDetailResponse, kept in USD instead of the winning listing's original currency. */
    private BigDecimal getCurrentPriceUsd(TrackedProduct product, Map<String, BigDecimal> rates) {
        List<ProductListing> listings = productListingRepository.findByTrackedProduct(product);
        BigDecimal lowestUsd = null;
        for (ProductListing listing : listings) {
            PricePoint latest = pricePointRepository.findTopByProductListingOrderByCheckedAtDesc(listing);
            if (latest == null) continue;
            BigDecimal priceUsd = exchangeRateService.convertToUsd(latest.getPrice(), latest.getCurrency(), rates);
            if (priceUsd == null) continue;
            if (lowestUsd == null || priceUsd.compareTo(lowestUsd) < 0) {
                lowestUsd = priceUsd;
            }
        }
        return lowestUsd;
    }
}