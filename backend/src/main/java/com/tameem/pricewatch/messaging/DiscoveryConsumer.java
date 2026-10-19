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
 * Runs cross-store discovery off the request path. One consumer group ("discovery-worker");
 * the message key is the product id, so the same product is never discovered by two threads
 * at once. Runs on a consumer thread — no HTTP/security context, no open Hibernate session —
 * so all DB work happens inside {@link TrackedProductService#runDiscovery}, which is
 * @Transactional and re-reads the product.
 *
 * At-least-once means this can be handed the same message twice (redelivery, rebalance), so
 * runDiscovery is idempotent (skip-if-DONE). Letting an exception propagate here is on
 * purpose: it tells Kafka the record was not processed, so it is redelivered. Bounded
 * retries + a dead-letter topic are the next step (reliability).
 */
@Component
public class DiscoveryConsumer {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryConsumer.class);
    private final TrackedProductService trackedProductService;
    private final ObjectMapper mapper = new ObjectMapper();

    public DiscoveryConsumer(TrackedProductService trackedProductService) {
        this.trackedProductService = trackedProductService;
    }

    @KafkaListener(topics = KafkaTopics.PRODUCT_TRACKED, groupId = "discovery-worker")
    public void onProductTracked(@Payload String payload,
                                 @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                                 @Header(KafkaHeaders.OFFSET) long offset) {
        ProductTrackedEvent event = mapper.readValue(payload, ProductTrackedEvent.class);
        log.info("Discovery consume: product {} (partition {} offset {})",
                event.trackedProductId(), partition, offset);
        trackedProductService.runDiscovery(event.trackedProductId());
    }
}
