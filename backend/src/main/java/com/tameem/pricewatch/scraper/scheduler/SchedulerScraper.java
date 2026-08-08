package com.tameem.pricewatch.scraper.scheduler;


import com.tameem.pricewatch.entity.ProductListing;
import com.tameem.pricewatch.repositories.ProductListingRepository;
import com.tameem.pricewatch.scraper.ScrapeException;
import com.tameem.pricewatch.service.TrackedProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component

public class SchedulerScraper {

    @Value("${pricewatch.scrape.interval}")
    private Duration interval;
    private final ProductListingRepository productListingRepository;
    private final TrackedProductService trackedProductService;
    private static final Logger log = LoggerFactory.getLogger(SchedulerScraper.class);


    public SchedulerScraper(ProductListingRepository productListingRepository, TrackedProductService trackedProductService) {
        this.productListingRepository = productListingRepository;
        this.trackedProductService = trackedProductService;
    }


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
            }
                 catch (ScrapeException e) {
                failed++;
                log.warn("Refresh failed for listing {} ({}): {}", listing.getId(), listing.getUrl(), e.getMessage());
            } catch ( InterruptedException e) {
                failed++;
                Thread.currentThread().interrupt();
                break;
            }
        }
        long timeAfterLoop = System.nanoTime();
        log.info("Sweep complete: {} listings checked, {} succeeded, {} failed, {}ms",listings.size(), succeeded, failed, (timeAfterLoop-timeBeforeLoop)/1_000_000);

    }
}
