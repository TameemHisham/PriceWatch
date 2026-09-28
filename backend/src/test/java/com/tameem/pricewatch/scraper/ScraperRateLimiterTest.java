package com.tameem.pricewatch.scraper;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-marketplace circuit breaker, driven by an injectable clock so cooldown transitions are
 * tested without sleeping. "MP" is a stand-in marketplace id.
 */
class ScraperRateLimiterTest {

    private static final String MP = "CURRYS";
    private static final Duration COOLDOWN = Duration.ofHours(6);

    /** A clock the test advances by hand. */
    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-28T00:00:00Z"));
    private final ScraperRateLimiter limiter = new ScraperRateLimiter(clock, COOLDOWN);

    @Test
    void breakerIsClosedByDefault() {
        assertFalse(limiter.isOpen(MP));
        assertEquals(ScraperRateLimiter.BreakerState.CLOSED, limiter.snapshot(MP).state());
    }

    @Test
    void anEdgeChallengeOpensTheBreakerImmediately() {
        limiter.recordEdgeChallenge(MP, "Cloudflare JS challenge — not retryable");

        assertTrue(limiter.isOpen(MP));
        ScraperRateLimiter.BreakerStatus status = limiter.snapshot(MP);
        assertEquals(ScraperRateLimiter.BreakerState.OPEN, status.state());
        assertEquals(clock.instant().plus(COOLDOWN), status.nextProbeAt());
    }

    @Test
    void anOpenBreakerRefusesAcquire() {
        limiter.recordEdgeChallenge(MP, "challenge");
        assertThrows(ScrapeException.class, () -> limiter.acquire(MP));
    }

    @Test
    void afterCooldownExactlyOneProbeIsAllowed() {
        limiter.recordEdgeChallenge(MP, "challenge");
        clock.advance(COOLDOWN); // cooldown elapsed → HALF_OPEN

        assertFalse(limiter.isOpen(MP), "half-open should not report as blocking");
        limiter.acquire(MP); // the one probe
        assertThrows(ScrapeException.class, () -> limiter.acquire(MP),
                "a second concurrent probe must be refused in HALF_OPEN");
    }

    @Test
    void aSuccessfulProbeClosesTheBreakerAndResetsTheCooldown() {
        limiter.recordEdgeChallenge(MP, "challenge"); // cooldown 6h
        clock.advance(COOLDOWN);
        limiter.acquire(MP);
        limiter.recordSuccess(MP);

        assertFalse(limiter.isOpen(MP));
        assertEquals(ScraperRateLimiter.BreakerState.CLOSED, limiter.snapshot(MP).state());

        // Re-opening starts from the base cooldown again, proving the reset.
        Instant openAt = clock.instant();
        limiter.recordEdgeChallenge(MP, "challenge");
        assertEquals(openAt.plus(COOLDOWN), limiter.snapshot(MP).nextProbeAt());
    }

    @Test
    void aReChallengeOnTheProbeDoublesTheCooldown() {
        limiter.recordEdgeChallenge(MP, "challenge"); // 6h
        clock.advance(COOLDOWN);
        limiter.acquire(MP); // probe

        Instant reopenAt = clock.instant();
        limiter.recordEdgeChallenge(MP, "challenge"); // probe failed → 12h
        assertEquals(reopenAt.plus(COOLDOWN.multipliedBy(2)), limiter.snapshot(MP).nextProbeAt());
    }

    @Test
    void theCooldownIsCappedAtTwentyFourHours() {
        limiter.recordEdgeChallenge(MP, "challenge"); // 6h  (open from CLOSED)
        limiter.recordEdgeChallenge(MP, "challenge"); // 12h (re-open while OPEN)
        limiter.recordEdgeChallenge(MP, "challenge"); // 24h
        Instant openAt = clock.instant();
        limiter.recordEdgeChallenge(MP, "challenge"); // 48h → capped at 24h

        assertEquals(openAt.plus(Duration.ofHours(24)), limiter.snapshot(MP).nextProbeAt());
    }

    @Test
    void anOrdinaryFailureNeverOpensTheBreaker() {
        limiter.recordFailure(MP);
        assertFalse(limiter.isOpen(MP));
        assertEquals(ScraperRateLimiter.BreakerState.CLOSED, limiter.snapshot(MP).state());
    }

    @Test
    void aSingleSoftCaptchaDoesNotOpenButThreeInARowDo() {
        limiter.recordCaptchaBlock(MP);
        limiter.recordCaptchaBlock(MP);
        assertFalse(limiter.isOpen(MP), "two consecutive CAPTCHAs stay below the threshold");

        limiter.recordCaptchaBlock(MP);
        assertTrue(limiter.isOpen(MP), "the third consecutive CAPTCHA opens the breaker");
    }

    @Test
    void aSuccessResetsTheSoftCaptchaCount() {
        limiter.recordCaptchaBlock(MP);
        limiter.recordCaptchaBlock(MP);
        limiter.recordSuccess(MP); // count back to zero

        limiter.recordCaptchaBlock(MP);
        limiter.recordCaptchaBlock(MP);
        assertFalse(limiter.isOpen(MP), "the count must have reset, so two more do not open it");
    }

    // ---- guard: one terminal outcome per call ----

    @Test
    void guardOpensOnAHardEdgeChallengeAndClosesOnSuccess() {
        assertThrows(EdgeChallengeException.class, () -> limiter.guard(MP, () -> {
            throw new EdgeChallengeException("Cloudflare JS challenge — not retryable");
        }));
        assertTrue(limiter.isOpen(MP));

        clock.advance(COOLDOWN);
        String result = limiter.guard(MP, () -> "ok"); // the probe succeeds
        assertEquals("ok", result);
        assertFalse(limiter.isOpen(MP));
    }

    @Test
    void guardTreatsASoftChallengeAsCaptchaThreshold() {
        for (int i = 0; i < 2; i++) {
            assertThrows(EdgeChallengeException.class, () -> limiter.guard(MP, () -> {
                throw new EdgeChallengeException("Amazon blocked request with CAPTCHA", true);
            }));
        }
        assertFalse(limiter.isOpen(MP), "two soft CAPTCHAs stay under the threshold");

        assertThrows(EdgeChallengeException.class, () -> limiter.guard(MP, () -> {
            throw new EdgeChallengeException("Amazon blocked request with CAPTCHA", true);
        }));
        assertTrue(limiter.isOpen(MP), "the third soft CAPTCHA opens the breaker");
    }

    @Test
    void guardDoesNotOpenOnAnOrdinaryFailure() {
        assertThrows(ScrapeException.class, () -> limiter.guard(MP, () -> {
            throw new ScrapeException("No product title on page");
        }));
        assertFalse(limiter.isOpen(MP), "an ordinary parse failure must not open the breaker");
    }
}
