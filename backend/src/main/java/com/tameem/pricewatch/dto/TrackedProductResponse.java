package com.tameem.pricewatch.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record TrackedProductResponse(
        Long id, String name, String brand, String category,
        BigDecimal targetPrice, Instant createdAt, String imageUrl,
        String currency, BigDecimal currentPrice, int storeCount, boolean targetPriceReached,
        List<BigDecimal> recentPrices , BigDecimal trendPercent,
        List<String> stores
) {}
