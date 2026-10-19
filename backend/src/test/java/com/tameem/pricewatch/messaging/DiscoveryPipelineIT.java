package com.tameem.pricewatch.messaging;

import com.tameem.pricewatch.entity.DiscoveryStatus;
import com.tameem.pricewatch.entity.TrackedProduct;
import com.tameem.pricewatch.entity.User;
import com.tameem.pricewatch.matching.CrossStoreDiscovery;
import com.tameem.pricewatch.repositories.TrackedProductRepository;
import com.tameem.pricewatch.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * End-to-end proof of the discovery pipeline over REAL Kafka + Postgres (Testcontainers):
 * publishing a product.tracked event makes the real DiscoveryConsumer run discovery and flip
 * the product PENDING -> DONE. Only the network (cross-store search) is stubbed, so the test
 * is deterministic — everything from the broker to the DB write is the real wiring.
 */
@SpringBootTest
@Testcontainers
class DiscoveryPipelineIT {

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    // Stub the only network call discovery would make; empty matches = nothing to attach.
    @MockitoBean
    private CrossStoreDiscovery discovery;

    @Autowired
    private TrackedProductRepository products;
    @Autowired
    private UserRepository users;
    @Autowired
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    @Test
    @SuppressWarnings("unchecked")
    void productTrackedEventDrivesDiscoveryToDone() {
        when(discovery.findMatches(any(), any())).thenReturn(Map.of());

        User owner = new User();
        owner.setEmail("it-" + UUID.randomUUID() + "@example.com");
        owner.setPasswordHash("x");
        owner.setCreatedAt(Instant.now());
        users.save(owner);

        TrackedProduct product = new TrackedProduct();
        product.setName("Integration Test Product");
        product.setUser(owner);
        product.setDiscoveryStatus(DiscoveryStatus.PENDING);
        long id = products.save(product).getId();

        String payload = "{\"eventId\":\"" + UUID.randomUUID()
                + "\",\"trackedProductId\":" + id
                + ",\"occurredAt\":\"" + Instant.now() + "\"}";
        kafkaTemplate.send(KafkaTopics.PRODUCT_TRACKED, String.valueOf(id), payload);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(products.findById(id).orElseThrow().getDiscoveryStatus())
                        .isEqualTo(DiscoveryStatus.DONE));
    }
}
