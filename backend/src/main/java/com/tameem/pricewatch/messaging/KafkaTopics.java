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

    @Bean
    public NewTopic productTrackedTopic() {
        return TopicBuilder.name(PRODUCT_TRACKED)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
