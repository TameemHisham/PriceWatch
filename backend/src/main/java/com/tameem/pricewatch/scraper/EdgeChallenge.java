package com.tameem.pricewatch.scraper;

import org.jsoup.Connection;

/**
 * Shared edge-challenge handling for scraper fetch paths.
 * <p>
 * A Cloudflare managed challenge announces itself in the {@code cf-mitigated} response header
 * ({@code cf-mitigated: challenge}) before any body is read. It is a browser/JS check Jsoup
 * cannot answer, so a challenged request is not a transient failure and not a fingerprint
 * mismatch another user agent might slip past — retrying only spends more requests against the
 * same wall. Any scraper that reads its own response (i.e. fetches with
 * {@code ignoreHttpErrors(true)}) should call {@link #failFastIfMitigated} before its retry
 * logic so the challenge stops the fetch immediately instead of rotating agents.
 */
public final class EdgeChallenge {

    private EdgeChallenge() {}

    /**
     * Throws {@link ScrapeException} at once if the response carries a Cloudflare mitigation
     * challenge, so the caller does not retry it. A no-op for any other response.
     */
    public static void failFastIfMitigated(Connection.Response response) {
        if (isMitigatedChallenge(response.header("cf-mitigated"))) {
            throw new ScrapeException("Cloudflare JS challenge — not retryable");
        }
    }

    /**
     * Whether a {@code cf-mitigated} header value names a challenge. Split out so the decision
     * can be exercised without a live {@link Connection.Response}. Cloudflare sends the bare
     * token {@code challenge}; matched case-insensitively and as a substring so a future
     * compound value ({@code challenge; ...}) is still caught.
     */
    static boolean isMitigatedChallenge(String cfMitigated) {
        return cfMitigated != null && cfMitigated.toLowerCase().contains("challenge");
    }
}
