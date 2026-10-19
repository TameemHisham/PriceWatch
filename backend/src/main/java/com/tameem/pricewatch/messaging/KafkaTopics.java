package com.tameem.pricewatch.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Topic names (constants, never string literals scattered around) and their declarations.
 * Auto-create is off in the broker, so Spring's KafkaAdmin creates these NewTopic beans on
 * startup. 3 partitions = up to 3 products discovered in parallel; replicas(1) because the
 * local broker is a single node. The partition count is fixed — changing it later rehashes
 * every key (Chunk 2).
 */
@Configuration
public class KafkaTopics {

    public static final String PRODUCT_TRACKED = "product.tracked";
    public static final String LISTING_REFRESH = "listing.refresh";
    // Dead-letter topics: a record that fails past its retries is parked here (not retried
    // forever, not silently dropped) with its original value + the exception in headers.
    // Same partition count as the source so the recoverer can keep the record's partition.
    public static final String PRODUCT_TRACKED_DLT = "product.tracked.DLT";
    public static final String LISTING_REFRESH_DLT = "listing.refresh.DLT";

    @Bean
    public NewTopic productTrackedTopic() {
        return TopicBuilder.name(PRODUCT_TRACKED)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic productTrackedDltTopic() {
        return TopicBuilder.name(PRODUCT_TRACKED_DLT).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic listingRefreshDltTopic() {
        return TopicBuilder.name(LISTING_REFRESH_DLT).partitions(12).replicas(1).build();
    }

    // One partition per marketplace lane, plus headroom: keyed by marketplace, and
    // hash(marketplace) % partitions must rarely collide two storefronts onto one lane
    // (a collision serialises them and dents the parallel speedup). 12 > the ~10 marketplaces.
    @Bean
    public NewTopic listingRefreshTopic() {
        return TopicBuilder.name(LISTING_REFRESH)
                .partitions(12)
                .replicas(1)
                .build();
    }
}
