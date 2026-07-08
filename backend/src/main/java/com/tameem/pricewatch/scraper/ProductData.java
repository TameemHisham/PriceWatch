package com.tameem.pricewatch.scraper;

import java.math.BigDecimal;

public record ProductData(
        String title,
        BigDecimal price,
        String currency,
        String imageUrl
) {}