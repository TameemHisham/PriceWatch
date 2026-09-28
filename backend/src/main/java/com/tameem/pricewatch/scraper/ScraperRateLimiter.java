package com.tameem.pricewatch.scraper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared throttle and per-marketplace circuit breaker, so every scraper passes through the same
 * budget and the same self-healing block handling instead of each racing the others onto a
 * storefront that is already blocking.
 * <p>
 * <b>Two block signals, one breaker.</b>
 * <ul>
 *   <li><i>Hard</i> — an {@link EdgeChallengeException}: a Cloudflare/Akamai managed challenge
 *       no HTTP client answers. {@link #recordEdgeChallenge} opens the breaker on the first
 *       one, because retrying is hopeless.</li>
 *   <li><i>Soft</i> — an Amazon {@code validateCaptcha} interstitial, which is often transient.
 *       {@link #recordCaptchaBlock} opens only after {@link #CAPTCHA_THRESHOLD} in a row, so a
 *       single blip does not park Amazon for hours.</li>
 * </ul>
 * Both then share the same cooldown machinery: OPEN for a cooldown (config
 * {@code pricewatch.scrape.breaker.cooldown}, default 6h), doubling on each consecutive re-open
 * up to a 24h cap; then HALF_OPEN, which lets exactly one probe request through; a success
 * closes the breaker and resets the cooldown, another challenge re-opens it.
 * <p>
 * Ordinary failures — timeouts, 404s, parse errors — never open the breaker: only the two
 * block signals above do.
 * <p>
 * State is in-memory and resets on restart. That is fine for a single instance; it is a
 * candidate to move to Redis in Phase 6 so a restart (or a second instance) does not forget an
 * open breaker and re-probe a storefront that is still blocking.
 */
@Component
public class ScraperRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ScraperRateLimiter.class);

    private static final Duration MIN_INTERVAL = Duration.ofSeconds(2);
    private static final int CAPTCHA_THRESHOLD = 3;
    private static final Duration MAX_COOLDOWN = Duration.ofHours(24);
    static final Duration DEFAULT_COOLDOWN = Duration.ofHours(6);

    public enum BreakerState { CLOSED, OPEN, HALF_OPEN }

    /** Read-only view of one marketplace's breaker, for the status endpoint. */
    public record BreakerStatus(BreakerState state, Instant openedAt, Instant nextProbeAt,
                                String lastError) {}

    private final Clock clock;
    private final Duration baseCooldown;
    private final Map<String, Breaker> breakers = new ConcurrentHashMap<>();

    /** Production constructor: cooldown from configuration, real clock. */
    @Autowired
    public ScraperRateLimiter(
            @Value("${pricewatch.scrape.breaker.cooldown:PT6H}") Duration baseCooldown) {
        this(Clock.systemUTC(), baseCooldown);
    }

    /** Kept so {@code new ScraperRateLimiter()} in existing tests still constructs the default. */
    public ScraperRateLimiter() {
        this(Clock.systemUTC(), DEFAULT_COOLDOWN);
    }

    /** Test seam: inject a fixed/adjustable clock and cooldown, so state can be driven without sleeps. */
    ScraperRateLimiter(Clock clock, Duration baseCooldown) {
        this.clock = clock;
        this.baseCooldown = baseCooldown == null ? DEFAULT_COOLDOWN : baseCooldown;
    }

    // ---- request path ----

    /**
     * Runs a fetch+parse under this marketplace's breaker, recording the one terminal outcome so
     * the breaker stays consistent (a HALF_OPEN probe is always resolved, never wedged):
     * <ul>
     *   <li>returns normally → {@link #recordSuccess} (edge is healthy → CLOSED);</li>
     *   <li>{@link EdgeChallengeException} → {@link #recordCaptchaBlock} if soft, else
     *       {@link #recordEdgeChallenge} (edge block → OPEN);</li>
     *   <li>any other failure → {@link #recordFailure} (inconclusive: a half-open probe returns
     *       to OPEN, a closed breaker stays closed — an ordinary fault never opens it).</li>
     * </ul>
     * {@link #acquire} runs first and may throw if the breaker is open; that happens before the
     * action and needs no outcome recorded.
     */
    public <T> T guard(String marketplaceId, java.util.function.Supplier<T> action) {
        acquire(marketplaceId);
        try {
            T result = action.get();
            recordSuccess(marketplaceId);
            return result;
        } catch (EdgeChallengeException e) {
            if (e.isSoft()) {
                recordCaptchaBlock(marketplaceId);
            } else {
                recordEdgeChallenge(marketplaceId, e.getMessage());
            }
            throw e;
        } catch (RuntimeException e) {
            recordFailure(marketplaceId);
            throw e;
        }
    }

    /** Blocks until it is this marketplace's turn; throws if the breaker is open (or a probe is
     *  already in flight in HALF_OPEN). Callers that want to skip quietly should consult
     *  {@link #isOpen} first rather than catching this. */
    public void acquire(String marketplaceId) {
        Breaker breaker = breakerFor(marketplaceId);
        breaker.enterOrThrow(marketplaceId);
        breaker.throttle();
    }

    /** A clean fetch+parse: close the breaker and reset its cooldown. */
    public void recordSuccess(String marketplaceId) {
        breakerFor(marketplaceId).onSuccess(marketplaceId);
    }

    /** A hard, non-retryable edge/bot challenge: open immediately (doubling if re-opening). */
    public void recordEdgeChallenge(String marketplaceId, String reason) {
        breakerFor(marketplaceId).onEdgeChallenge(marketplaceId, reason);
    }

    /** A soft Amazon CAPTCHA: open only after {@link #CAPTCHA_THRESHOLD} consecutive. */
    public void recordCaptchaBlock(String marketplaceId) {
        breakerFor(marketplaceId).onCaptcha(marketplaceId);
    }

    /** An inconclusive, non-edge failure (timeout, 404, parse error). Never opens a closed
     *  breaker; only resolves a half-open probe by returning it to OPEN so it does not wedge. */
    public void recordFailure(String marketplaceId) {
        breakerFor(marketplaceId).onFailure(marketplaceId);
    }

    // ---- query path (for discovery / scheduler / status) ----

    /** Whether this marketplace's breaker is currently blocking requests (cooldown not elapsed). */
    public boolean isOpen(String marketplaceId) {
        Breaker breaker = breakers.get(marketplaceId);
        return breaker != null && breaker.isBlocking();
    }

    /** Short human reason the breaker is open, or null when it is not. */
    public String openReason(String marketplaceId) {
        Breaker breaker = breakers.get(marketplaceId);
        return breaker != null && breaker.isBlocking() ? breaker.lastError() : null;
    }

    /** Immutable snapshot for {@code GET /api/scrapers/status}. */
    public BreakerStatus snapshot(String marketplaceId) {
        Breaker breaker = breakers.get(marketplaceId);
        return breaker == null
                ? new BreakerStatus(BreakerState.CLOSED, null, null, null)
                : breaker.snapshot();
    }

    private Breaker breakerFor(String marketplaceId) {
        return breakers.computeIfAbsent(marketplaceId, id -> new Breaker());
    }

    /**
     * One marketplace's breaker. All state transitions synchronize on the instance; the throttle
     * reservation is lock-free and the sleep happens outside any lock.
     */
    private final class Breaker {
        private BreakerState state = BreakerState.CLOSED;
        private Instant openedAt;
        private Duration cooldown = baseCooldown;
        private String lastError;
        private int consecutiveCaptchas;
        private boolean probeInFlight;
        private final AtomicReference<Instant> lastRequestAt = new AtomicReference<>(Instant.EPOCH);

        synchronized void enterOrThrow(String marketplaceId) {
            maybeHalfOpen();
            switch (state) {
                case OPEN -> throw new ScrapeException(marketplaceId
                        + " circuit open (" + lastError + ") — cooling down until " + nextProbeAt());
                case HALF_OPEN -> {
                    if (probeInFlight) {
                        throw new ScrapeException(marketplaceId
                                + " circuit half-open — a probe is already in flight");
                    }
                    probeInFlight = true; // this caller is the single probe
                    log.info("{} half-open — allowing one probe request", marketplaceId);
                }
                case CLOSED -> { /* proceed */ }
            }
        }

        synchronized void onSuccess(String marketplaceId) {
            if (state != BreakerState.CLOSED) {
                log.info("{} recovered — closing circuit, resetting cooldown", marketplaceId);
            }
            state = BreakerState.CLOSED;
            openedAt = null;
            cooldown = baseCooldown;
            lastError = null;
            consecutiveCaptchas = 0;
            probeInFlight = false;
        }

        synchronized void onEdgeChallenge(String marketplaceId, String reason) {
            boolean reopen = state != BreakerState.CLOSED;
            open(reason == null ? "edge challenge" : reason, reopen);
            log.warn("{} hit an edge challenge ({}) — opening circuit for {}{}",
                    marketplaceId, lastError, cooldown, reopen ? " (re-open, cooldown doubled)" : "");
        }

        synchronized void onCaptcha(String marketplaceId) {
            if (state == BreakerState.HALF_OPEN) {
                // A CAPTCHA during the single probe means still blocked: re-open, doubling.
                open("CAPTCHA", true);
                log.warn("{} CAPTCHA on half-open probe — re-opening circuit for {}",
                        marketplaceId, cooldown);
                return;
            }
            consecutiveCaptchas++;
            if (consecutiveCaptchas >= CAPTCHA_THRESHOLD) {
                open("CAPTCHA", false);
                log.warn("{} hit {} consecutive CAPTCHA blocks — opening circuit for {}",
                        marketplaceId, consecutiveCaptchas, cooldown);
            }
        }

        synchronized void onFailure(String marketplaceId) {
            if (state == BreakerState.HALF_OPEN) {
                // The single probe failed for a non-edge reason: inconclusive. Keep cooling
                // (no double, the edge was never confirmed blocking), and free the probe slot.
                state = BreakerState.OPEN;
                openedAt = clock.instant();
                probeInFlight = false;
                log.debug("{} half-open probe failed (non-edge) — staying OPEN until {}",
                        marketplaceId, nextProbeAt());
            }
            // CLOSED stays CLOSED: an ordinary failure never opens the breaker.
        }

        /** Opens (or re-opens) the breaker. On a re-open the cooldown doubles, capped at 24h. */
        private void open(String reason, boolean reopen) {
            if (reopen) {
                Duration doubled = cooldown.multipliedBy(2);
                cooldown = doubled.compareTo(MAX_COOLDOWN) > 0 ? MAX_COOLDOWN : doubled;
            } else {
                cooldown = baseCooldown;
            }
            state = BreakerState.OPEN;
            openedAt = clock.instant();
            lastError = reason;
            probeInFlight = false;
        }

        /** If the cooldown has elapsed on an OPEN breaker, move it to HALF_OPEN. No logging:
         *  this is called from read paths (isOpen/snapshot) too, and must stay side-effect-quiet. */
        private void maybeHalfOpen() {
            if (state == BreakerState.OPEN && !clock.instant().isBefore(nextProbeAt())) {
                state = BreakerState.HALF_OPEN;
                probeInFlight = false;
            }
        }

        synchronized boolean isBlocking() {
            maybeHalfOpen();
            return state == BreakerState.OPEN;
        }

        synchronized String lastError() {
            return lastError;
        }

        synchronized BreakerStatus snapshot() {
            maybeHalfOpen();
            return new BreakerStatus(state, openedAt,
                    state == BreakerState.CLOSED ? null : nextProbeAt(), lastError);
        }

        private Instant nextProbeAt() {
            return openedAt == null ? null : openedAt.plus(cooldown);
        }

        /** Reserves the next slot MIN_INTERVAL after the previous one and sleeps until then. */
        void throttle() {
            Instant now = clock.instant();
            Instant reserved = lastRequestAt.updateAndGet(previous -> {
                Instant earliest = previous.plus(MIN_INTERVAL);
                return now.isAfter(earliest) ? now : earliest;
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
    }
}
