package com.tameem.pricewatch.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.CompletableFuture;

/**
 * Sends the Kafka message — but only AFTER the tracking transaction commits.
 *
 * The service publishes {@link ProductTrackedEvent} as an in-process Spring event while still
 * inside its @Transactional method. Spring holds it and fires this listener only once the DB
 * commit succeeds. So a rolled-back track emits no message — no phantom event for a product
 * that never existed.
 *
 * The gap this does NOT close (documented, not fixed in Phase 5): DB commits, then the app
 * dies or Kafka is down before the send lands. The product stays PENDING with no event. We
 * log that at ERROR here; the robust fix is a transactional outbox.
 */
@Component
public class ProductTrackedPublisher {

    private static final Logger log = LoggerFactory.getLogger(ProductTrackedPublisher.class);
    // Raw type on purpose: Boot autoconfigures a single KafkaTemplate<?, ?> whose concrete
    // generics a KafkaTemplate<String, String> injection point won't match. Raw matches the
    // one bean; both serializers are String (application.properties), so sending Strings is safe.
    private final KafkaTemplate kafkaTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProductTrackedPublisher(KafkaTemplate kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @SuppressWarnings("unchecked")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProductTracked(ProductTrackedEvent event) {
        String key = String.valueOf(event.trackedProductId());
        String payload;
        try {
            payload = mapper.writeValueAsString(event);
        } catch (RuntimeException e) {
            log.error("Could not serialize ProductTrackedEvent for product {}: {}",
                    event.trackedProductId(), e.toString());
            return;
        }
        CompletableFuture<SendResult<String, String>> future =
                kafkaTemplate.send(KafkaTopics.PRODUCT_TRACKED, key, payload);
        future.whenComplete((result, ex) -> {
                    if (ex != null) {
                        // Dual-write gap made visible: the product is committed but no event
                        // reached Kafka, so discovery will never run for it until re-published.
                        log.error("Kafka send FAILED for product {} — discovery will not run: {}",
                                event.trackedProductId(), ex.toString());
                    } else {
                        var md = result.getRecordMetadata();
                        log.debug("Published product.tracked for {} -> {}-{}@{}",
                                event.trackedProductId(), md.topic(), md.partition(), md.offset());
                    }
                });
    }
}
