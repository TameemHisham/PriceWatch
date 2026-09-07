package com.tameem.pricewatch.service;

import com.tameem.pricewatch.dto.CurrencyResponse;
import com.tameem.pricewatch.entity.ExchangeRate;
import com.tameem.pricewatch.repositories.ExchangeRateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ExchangeRateService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateService.class);

    /**
     * Currencies already reported as missing a rate. A dashboard load converts every
     * listing, so warning on each occurrence would emit the same line dozens of times per
     * request; once per currency per process is loud enough to notice without drowning the
     * log, and a restart re-reports anything still unfixed.
     */
    private final Set<String> reportedMissingRates = ConcurrentHashMap.newKeySet();

    private final ExchangeRateRepository exchangeRateRepository;

    public ExchangeRateService(ExchangeRateRepository exchangeRateRepository) {
        this.exchangeRateRepository = exchangeRateRepository;
    }

    public List<CurrencyResponse> getCurrentExchangeRate() {
        List<ExchangeRate> currencies = exchangeRateRepository.findAll();
        return currencies.stream()
                .map(c -> new CurrencyResponse(c.getCurrency(), c.getExchangeRate()))
                .toList();
    }

    /** Builds the ASIN -> USD rate lookup once per call, so a comparison loop doesn't hit the DB per listing. */
    public Map<String, BigDecimal> currentRatesByCurrency() {
        return exchangeRateRepository.findAll().stream()
                .collect(Collectors.toMap(ExchangeRate::getCurrency, ExchangeRate::getExchangeRate));
    }

    public BigDecimal convertToUsd(BigDecimal price, String currency, Map<String, BigDecimal> rates) {
        return convertToUsd(price, currency, rates, null);
    }

    /**
     * Converts an observed price to USD, or returns null when it cannot be compared.
     * <p>
     * A missing rate is a silent failure otherwise: the price is stored correctly, but every
     * caller skips the listing and the API reports a null price for a product that has one.
     * That is exactly how the first SAR store looked like a working scrape with no price.
     * Adding a storefront in a new currency means adding it to the quote list in
     * SchedulerScraper.scrapeCurrency() — and that sweep is weekly, so the gap can persist.
     *
     * @param listingId identifies what could not be converted; may be null when the caller
     *                  has no listing in hand.
     */
    public BigDecimal convertToUsd(BigDecimal price, String currency,
                                   Map<String, BigDecimal> rates, Long listingId) {
        if (price == null || currency == null) return null;
        BigDecimal exchangeRate = rates.get(currency);
        if (exchangeRate == null) {
            if (reportedMissingRates.add(currency)) {
                log.warn("No exchange rate for currency {} (first seen on listing {}) — prices in {}"
                                + " are stored but left out of every USD comparison, so the API will"
                                + " report them as null. Add {} to the quotes list in"
                                + " SchedulerScraper.scrapeCurrency().",
                        currency, listingId, currency, currency);
            }
            return null;
        }
        return price.divide(exchangeRate, 2, RoundingMode.HALF_UP);
    }
}