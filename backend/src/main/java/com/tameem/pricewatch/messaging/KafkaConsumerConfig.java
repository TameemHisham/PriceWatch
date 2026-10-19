package com.tameem.pricewatch.messaging;

import com.tameem.pricewatch.scraper.ScrapeException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * One error handler for every @KafkaListener (Boot wires a single DefaultErrorHandler bean
 * into the default container factory). Replaces spring-kafka's default of retrying a failing
 * record 10× instantly — which, on a scrape that throws, meant hammering a retailer 10× and
 * blocking its whole lane (head-of-line) while it did.
 *
 * Policy:
 *  - ScrapeException is NON-retryable. A parse/no-price failure is deterministic — retrying
 *    just repeats the same failed request — and an edge/bot challenge should back off (the
 *    circuit breaker and the next sweep handle recovery), not be retried. So it goes straight
 *    to the DLT after one attempt. This is the politeness fix: one request per listing, as the
 *    old sequential sweep did.
 *  - Anything else (timeouts, transient infra) gets 2 short retries, then the DLT.
 *
 * The record keeps its original key/value; the recoverer sends it to "<topic>.DLT" on the
 * same partition, with the exception recorded in headers.
 */
@Configuration
public class KafkaConsumerConfig {

    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) ->
                        new TopicPartition(record.topic() + ".DLT", record.partition()));

        // interval 1s, 2 retries → 3 attempts max for retryable exceptions.
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
        handler.addNotRetryableExceptions(ScrapeException.class);
        return handler;
    }
}
