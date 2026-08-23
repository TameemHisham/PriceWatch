package com.tameem.pricewatch.scraper;

import java.math.BigDecimal;

public record ProductData(
        String title,
        BigDecimal price,
        String currency,
        String imageUrl,
        Availability availability
) {
    /** True when this observation carried a usable price. */
    public boolean hasPrice() {
        return availability == Availability.AVAILABLE && price != null;
    }
}
