package com.tameem.pricewatch.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** convertToUsd needs no repository, so it is exercised directly. */
class ExchangeRateServiceTest {

    private final ExchangeRateService service = new ExchangeRateService(null);

    private static final Map<String, BigDecimal> RATES = Map.of(
            "USD", new BigDecimal("1.00"),
            "GBP", new BigDecimal("0.74"),
            "SAR", new BigDecimal("3.75"));

    @Test
    void convertsAKnownCurrency() {
        assertEquals(0, new BigDecimal("10.40").compareTo(
                service.convertToUsd(new BigDecimal("39.00"), "SAR", RATES, 1L)));
    }

    /** The silent-null case the warning exists for: stored price, no rate, no comparison. */
    @Test
    void returnsNullForACurrencyWithNoRate() {
        assertNull(service.convertToUsd(new BigDecimal("39.00"), "PLN", RATES, 42L));
    }

    /** Warned once per currency, so a second call must not change the outcome. */
    @Test
    void staysNullOnRepeatedMissingRateLookups() {
        assertNull(service.convertToUsd(new BigDecimal("1.00"), "INR", RATES, 1L));
        assertNull(service.convertToUsd(new BigDecimal("2.00"), "INR", RATES, 2L));
    }

    @Test
    void returnsNullForNullPriceOrCurrency() {
        assertNull(service.convertToUsd(null, "GBP", RATES, 1L));
        assertNull(service.convertToUsd(new BigDecimal("5.00"), null, RATES, 1L));
    }
}
