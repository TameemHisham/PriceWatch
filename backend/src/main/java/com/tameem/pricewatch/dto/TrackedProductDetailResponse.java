package com.tameem.pricewatch.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record TrackedProductDetailResponse(Long id, String name, String brand, String category,
                                           BigDecimal targetPrice, Instant createdAt, String imageUrl,
                                           String currency, BigDecimal currentPrice, int storeCount, List<ListingResponse> listings,boolean targetPriceReached) {

}
