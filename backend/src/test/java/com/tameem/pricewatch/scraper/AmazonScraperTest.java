package com.tameem.pricewatch.scraper;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Focused tests for the locale-aware price parser — the part most likely to
 * silently produce a wrong number. No Spring context or network needed.
 */
class AmazonScraperTest {

    private final AmazonScraper scraper = new AmazonScraper();

    @Test
    void parsesUkAndUsFormat() {
        // dot = decimal, comma = thousands
        assertEquals(new BigDecimal("24.99"), scraper.toBigDecimal("£24.99"));
        assertEquals(new BigDecimal("1299.00"), scraper.toBigDecimal("$1,299.00"));
    }

    @Test
    void parsesEuroFormat() {
        // comma = decimal, dot = thousands (the case the old code got 100x wrong)
        assertEquals(new BigDecimal("24.99"), scraper.toBigDecimal("24,99 €"));
        assertEquals(new BigDecimal("1299.00"), scraper.toBigDecimal("1.299,00 €"));
    }

    @Test
    void parsesThousandsWithoutDecimals() {
        // "1,299" has 3 trailing digits -> treated as a thousands group, not decimals
        assertEquals(new BigDecimal("1299"), scraper.toBigDecimal("1,299"));
        assertEquals(new BigDecimal("1299"), scraper.toBigDecimal("1.299"));
    }

    @Test
    void returnsNullWhenNoDigits() {
        assertNull(scraper.toBigDecimal("Currently unavailable"));
    }

    @Test
    void supportsOnlyRealAmazonProductUrls() {
        assertEquals(true, scraper.supports("https://www.amazon.co.uk/dp/B0ABC12345"));
        assertEquals(true, scraper.supports("https://amazon.de/gp/product/B0ABC12345"));
        // host is evil.com, "amazon." only appears in the path -> must be rejected
        assertEquals(false, scraper.supports("https://evil.com/amazon./dp/B0ABC12345"));
        assertEquals(false, scraper.supports("https://www.amazon.co.uk/gp/cart"));
        assertEquals(false, scraper.supports(null));
    }
}
