package com.tameem.pricewatch.scraper.scheduler;


import com.tameem.pricewatch.entity.ExchangeRate;
import com.tameem.pricewatch.entity.ProductListing;
import com.tameem.pricewatch.repositories.ExchangeRateRepository;
import com.tameem.pricewatch.repositories.ProductListingRepository;
import com.tameem.pricewatch.scraper.ScrapeException;
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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Component
public class SchedulerScraper {

    @Value("${pricewatch.scrape.interval}")
    private Duration interval;
    private final ProductListingRepository productListingRepository;
    private final TrackedProductService trackedProductService;
    private  final  RestClient restClient;

    private final ExchangeRateRepository exchangeRateRepository;

    private static final Logger log = LoggerFactory.getLogger(SchedulerScraper.class);

    public SchedulerScraper(ProductListingRepository productListingRepository, TrackedProductService trackedProductService,ExchangeRateRepository exchangeRateRepository) {
        this.productListingRepository = productListingRepository;
        this.trackedProductService = trackedProductService;
        this.exchangeRateRepository = exchangeRateRepository;
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

        int succeeded = 0;
        int failed = 0;
        long timeBeforeLoop = System.nanoTime();
        for (ProductListing listing : listings) {
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
            long timeAfterLoop = System.nanoTime();
            log.info("Sweep complete: {} listings checked, {} succeeded, {} failed, {}ms", listings.size(), succeeded, failed, (timeAfterLoop - timeBeforeLoop) / 1_000_000);
        }
    }

    @Scheduled(cron = "@weekly")
//    @Scheduled(fixedRate = 10000)
    public void scrapeCurrency() {
            try {
                currencyDTO[] currencies = restClient.get().uri("?base=USD&quotes=USD,GBP,AED").retrieve().body(currencyDTO[].class);
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
