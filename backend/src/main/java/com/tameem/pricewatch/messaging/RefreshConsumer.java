package com.tameem.pricewatch.messaging;

import com.tameem.pricewatch.service.TrackedProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Re-scrapes listings off the scheduler thread. The scheduled sweep now only publishes jobs;
 * these consumers do the work, in parallel across marketplaces.
 *
 * concurrency=12 matches the topic's partition count, so up to 12 lanes run at once. Because
 * the topic is keyed by marketplace, one lane carries exactly one storefront, handled by one
 * consumer thread. Pausing 2s at the end of each record therefore spaces consecutive scrapes
 * of the SAME marketplace — the politeness the old sequential sweep enforced globally — while
 * other marketplaces proceed concurrently. That is the whole throughput win.
 *
 * A "no offer" result is not an error (refreshListing records the check and returns), so it
 * never reaches here as a failure. A genuine scrape failure propagates, so the record is
 * redelivered (at-least-once); re-scraping is idempotent enough for Phase 5 — a redelivery
 * writes one extra price point, a harmless duplicate observation. Bounded retries + DLT are
 * the reliability follow-up.
 */
@Component
public class RefreshConsumer {

    private static final Logger log = LoggerFactory.getLogger(RefreshConsumer.class);
    private static final long POLITENESS_PAUSE_MS = 2000;

    private final TrackedProductService trackedProductService;
    private final ObjectMapper mapper = new ObjectMapper();

    public RefreshConsumer(TrackedProductService trackedProductService) {
        this.trackedProductService = trackedProductService;
    }

    @KafkaListener(topics = KafkaTopics.LISTING_REFRESH, groupId = "refresh-worker", concurrency = "12")
    public void onListingRefresh(@Payload String payload,
                                 @Header(KafkaHeaders.RECEIVED_PARTITION) int partition)
            throws InterruptedException {
        ListingRefreshEvent event = mapper.readValue(payload, ListingRefreshEvent.class);
        log.debug("Refresh consume: listing {} on {} (partition {})",
                event.listingId(), event.marketplace(), partition);
        try {
            trackedProductService.refreshListingById(event.listingId());
        } finally {
            // Per-lane politeness: this thread owns this partition (one marketplace).
            Thread.sleep(POLITENESS_PAUSE_MS);
        }
    }
}
