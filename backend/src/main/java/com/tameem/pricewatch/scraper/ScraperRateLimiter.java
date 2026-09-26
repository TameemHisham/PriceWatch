package com.tameem.pricewatch.scraper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared throttle and circuit breaker, keyed per marketplace, so every caller passes through
 * the same budget instead of each accidentally racing the others onto the same store.
 */
@Component
public class ScraperRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ScraperRateLimiter.class);

    private static final Duration MIN_INTERVAL = Duration.ofSeconds(2);
    private static final int CAPTCHA_THRESHOLD = 3;
    private static final Duration COOLDOWN = Duration.ofMinutes(5);

    private final Map<String, AtomicReference<Instant>> lastRequestAt = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> consecutiveCaptchas = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<Instant>> circuitOpenedAt = new ConcurrentHashMap<>();

    /** Blocks until it is this marketplace's turn; throws if the circuit is open. */
    public void acquire(String marketplaceId) {
        checkCircuit(marketplaceId);
        throttle(marketplaceId);
    }

    private void checkCircuit(String marketplaceId) {
        AtomicReference<Instant> openedAt = circuitOpenedAt.get(marketplaceId);
        if (openedAt == null || openedAt.get() == null) return;

        Instant opened = openedAt.get();
        if (Instant.now().isBefore(opened.plus(COOLDOWN))) {
            throw new ScrapeException(marketplaceId + " circuit open after repeated CAPTCHA "
                    + "blocks — cooling down until " + opened.plus(COOLDOWN));
        }

        log.info("Cooldown elapsed for {} — closing circuit, resetting CAPTCHA count", marketplaceId);
        openedAt.set(null);
        consecutiveCaptchas.computeIfAbsent(marketplaceId, id -> new AtomicInteger()).set(0);
    }

    private void throttle(String marketplaceId) {
        AtomicReference<Instant> last = lastRequestAt.computeIfAbsent(
                marketplaceId, id -> new AtomicReference<>(Instant.EPOCH));

        Instant now = Instant.now();
        Instant reserved = last.updateAndGet(previous -> {
            Instant earliestAllowed = previous.plus(MIN_INTERVAL);
            return now.isAfter(earliestAllowed) ? now : earliestAllowed;
        });

        long waitMillis = Duration.between(now, reserved).toMillis();
        if (waitMillis > 0) {
            try {
                Thread.sleep(waitMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void recordCaptchaBlock(String marketplaceId) {
        int count = consecutiveCaptchas.computeIfAbsent(marketplaceId, id -> new AtomicInteger())
                .incrementAndGet();
        if (count >= CAPTCHA_THRESHOLD) {
            circuitOpenedAt.computeIfAbsent(marketplaceId, id -> new AtomicReference<>())
                    .set(Instant.now());
            log.warn("{} hit {} consecutive CAPTCHA blocks — opening circuit for {}",
                    marketplaceId, count, COOLDOWN);
        }
    }

    public void recordSuccess(String marketplaceId) {
        AtomicInteger count = consecutiveCaptchas.get(marketplaceId);
        if (count != null) count.set(0);
    }
}