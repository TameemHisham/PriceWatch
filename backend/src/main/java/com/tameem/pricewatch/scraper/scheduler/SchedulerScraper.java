package com.tameem.pricewatch.scraper.scheduler;


import com.tameem.pricewatch.config.MarketplaceRegistry;
import com.tameem.pricewatch.entity.ExchangeRate;
import com.tameem.pricewatch.entity.ProductListing;
import com.tameem.pricewatch.repositories.ExchangeRateRepository;
import com.tameem.pricewatch.repositories.ProductListingRepository;
import com.tameem.pricewatch.scraper.ScrapeException;
import com.tameem.pricewatch.scraper.ScraperRateLimiter;
import com.tameem.pricewatch.service.TrackedProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class SchedulerScraper {

    @Value("${pricewatch.scrape.interval}")
    private Duration interval;
    private final ProductListingRepository productListingRepository;
    private final TrackedProductService trackedProductService;
    private  final  RestClient restClient;

    private final ExchangeRateRepository exchangeRateRepository;
    private final MarketplaceRegistry marketplaces;
    private final ScraperRateLimiter rateLimiter;

    private static final Logger log = LoggerFactory.getLogger(SchedulerScraper.class);
    private static final DateTimeFormatter PROBE_TIME =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    public SchedulerScraper(ProductListingRepository productListingRepository, TrackedProductService trackedProductService,ExchangeRateRepository exchangeRateRepository, MarketplaceRegistry marketplaces, ScraperRateLimiter rateLimiter) {
        this.productListingRepository = productListingRepository;
        this.trackedProductService = trackedProductService;
        this.exchangeRateRepository = exchangeRateRepository;
        this.marketplaces = marketplaces;
        this.rateLimiter = rateLimiter;
        this.restClient= RestClient.builder()
                .baseUrl("https://api.frankfurter.dev/v2/rates")
                .build();
    };


    //    21,600,000 ms =  6 hours
    @Scheduled(fixedDelayString="${pricewatch.scrape.interval}", initialDelayString = "${pricewatch.scrape.initial-delay}")
    public void scrape() {
        Instant currentInterval = Instant.now().minus(interval);
        List<ProductListing> listings = productListingRepository.findByLastCheckedBeforeOrLastCheckedIsNull(currentInterval);
        if (listings.isEmpty()) {
            log.info("\nNothing to scrape");
            return;
        };

        // Drop listings on marketplaces that are disabled (hard manual override) or whose circuit
        // breaker is open (self-healing skip). One DEBUG line per marketplace per reason per sweep
        // — not a WARN per listing, which a parked storefront like B&H would otherwise spray
        // across every sweep. Disabled wins over the breaker.
        List<ProductListing> toScrape = new ArrayList<>(listings.size());
        Map<String, Integer> skippedDisabled = new LinkedHashMap<>();
        Map<String, Integer> skippedOpen = new LinkedHashMap<>();
        for (ProductListing listing : listings) {
            String marketplaceId = listing.getMarketplace();
            if (marketplaceId != null && !marketplaces.isEnabled(marketplaceId)) {
                skippedDisabled.merge(marketplaceId, 1, Integer::sum);
            } else if (marketplaceId != null && rateLimiter.isOpen(marketplaceId)) {
                skippedOpen.merge(marketplaceId, 1, Integer::sum);
            } else {
                toScrape.add(listing);
            }
        }
        skippedDisabled.forEach((marketplaceId, count) ->
                log.debug("Skipping {} listings on disabled marketplace {}", count, marketplaceId));
        skippedOpen.forEach((marketplaceId, count) ->
                log.debug("Skipping {} listings on circuit-broken marketplace {} ({})",
                        count, marketplaceId, rateLimiter.openReason(marketplaceId)));

        int succeeded = 0;
        int failed = 0;
        long timeBeforeLoop = System.nanoTime();
        for (ProductListing listing : toScrape) {
            try {
                if (failed + succeeded != 0)
                    Thread.sleep(2000);
                trackedProductService.refreshListing(listing);
                succeeded++;
            } catch (InterruptedException e) {
                failed++;
                Thread.currentThread().interrupt();
            } catch (ScrapeException e) {
                failed++;
                log.warn("Refresh failed for listing {} ({}): {}", listing.getId(), listing.getUrl(), e.toString());
            } catch (Exception e) {
                failed++;
                log.error("Refresh failed for listing {} ({}): {}", listing.getId(), listing.getUrl(), e.toString());
            }
        }
        long timeAfterLoop = System.nanoTime();
        log.info("Sweep complete: {} listings checked, {} succeeded, {} failed, {}ms", toScrape.size(), succeeded, failed, (timeAfterLoop - timeBeforeLoop) / 1_000_000);
        logBreakerSummary();
    }

    /** One INFO line a sweep naming every open breaker and its probe time, e.g.
     *  "Breakers: CURRYS=OPEN(until 19:20), rest CLOSED". */
    private void logBreakerSummary() {
        String open = marketplaces.allMarketplaceIds().stream()
                .filter(rateLimiter::isOpen)
                .map(id -> {
                    Instant probeAt = rateLimiter.snapshot(id).nextProbeAt();
                    return id + "=OPEN(until " + (probeAt == null ? "?" : PROBE_TIME.format(probeAt)) + ")";
                })
                .collect(Collectors.joining(", "));
        log.info("Breakers: {}", open.isEmpty() ? "all CLOSED" : open + ", rest CLOSED");
    }

    @Scheduled(cron = "@weekly")
//    @Scheduled(fixedRate = 10000)
    public void scrapeCurrency() {
            try {
                // EUR and JPY are here because the scrapers can observe them: Amazon renders
                // imported offers in the visitor's currency, so a Euro or Yen price is a real
                // observation, not a symbol typo. Without a rate for them the price stores fine
                // but drops out of every USD comparison — the same UNKNOWN-shaped gap the GBP/USD
                // currency-parsing fix closed on the scraping side.
                currencyDTO[] currencies = restClient.get().uri("?base=USD&quotes=USD,GBP,AED,SAR,INR,EUR,JPY").retrieve().body(currencyDTO[].class);
                assert currencies != null;
                log.info("Currencies: " + Arrays.toString(currencies));
                for (currencyDTO currency : currencies) {
                    ExchangeRate rate = exchangeRateRepository
                            .findById(currency.quote())
                            .orElseGet(() -> {
                                ExchangeRate newRate = new ExchangeRate();
                                newRate.setCurrency(currency.quote());
                                return newRate;
                            });

                    rate.setExchangeRate(currency.rate());

                    exchangeRateRepository.save(rate);
                }
            } catch (RestClientResponseException e) {
                log.error("Error scraping currencies:  {}",e.toString());

            }
        }

}
