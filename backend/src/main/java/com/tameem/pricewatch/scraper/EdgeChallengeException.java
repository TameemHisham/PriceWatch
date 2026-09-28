package com.tameem.pricewatch.scraper;

/**
 * A bot/edge block that a plain HTTP client cannot get past — a Cloudflare or Akamai managed
 * challenge, a CAPTCHA interstitial, a "Just a moment…" holding page. Distinct from a generic
 * {@link ScrapeException} so callers can treat it as <em>non-retryable</em>: no user-agent
 * rotation, proxy swap or immediate re-request answers a JS/browser check, so the only useful
 * response is to stop and let the per-marketplace circuit breaker cool the storefront down.
 * <p>
 * Extends {@link ScrapeException} so every existing {@code catch (ScrapeException)} still
 * handles it; the subtype only adds the "this was a block, not a transient fault" signal that
 * {@link ScraperRateLimiter} keys its edge-open decision on.
 */
public class EdgeChallengeException extends ScrapeException {

    /**
     * A <i>soft</i> block is one that is often transient — Amazon's {@code validateCaptcha}
     * interstitial — where a single occurrence should not park the store for hours; the breaker
     * counts these to a threshold before opening. A <i>hard</i> block (the default) is a managed
     * Cloudflare/Akamai challenge, which opens the breaker on the first sight.
     */
    private final boolean soft;

    public EdgeChallengeException(String message) {
        this(message, false);
    }

    public EdgeChallengeException(String message, boolean soft) {
        super(message);
        this.soft = soft;
    }

    public EdgeChallengeException(String message, Throwable cause) {
        super(message, cause);
        this.soft = false;
    }

    public boolean isSoft() {
        return soft;
    }
}
