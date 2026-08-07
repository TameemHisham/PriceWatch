package com.tameem.pricewatch.scraper;

import java.math.BigDecimal;

/**
 * One observation of a product page. {@code price} and {@code currency} are null
 * when {@code availability} is UNAVAILABLE — the page carried no offer to read.
 */
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
