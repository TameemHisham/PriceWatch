package com.tameem.pricewatch.scraper;

import org.jsoup.Connection;

import java.util.Locale;

/**
 * Shared edge-challenge detection for scraper fetch paths.
 * <p>
 * A managed bot check announces itself in one of two ways, both handled here so every scraper
 * recognises a block the same way instead of each carrying its own partial markers:
 * <ul>
 *   <li>the {@code cf-mitigated: challenge} response header (Cloudflare states it outright,
 *       and it costs nothing to read); or</li>
 *   <li>a {@code 403}/{@code 503} whose title or body carries a vendor interstitial marker —
 *       Cloudflare's "Just a moment" / "cf-chl", Akamai's "Access Denied", the generic
 *       "Attention Required".</li>
 * </ul>
 * None of these is retryable: no user-agent rotation or proxy swap answers a JS/browser check,
 * so {@link #check} throws {@link EdgeChallengeException} on the first sight of one and the
 * caller stops. A body is only materialised for a status that could plausibly be a challenge,
 * so a healthy fetch never pays for the marker scan.
 */
public final class EdgeChallenge {

    private EdgeChallenge() {}

    /** Case-insensitive substrings that only appear on a served challenge/interstitial page.
     *  Cloudflare's challenge script id shows up as both {@code cf-chl-bypass} and the JS
     *  variable {@code _cf_chl_opt}, so both hyphen and underscore forms are matched. */
    private static final String[] BODY_MARKERS = {
            "just a moment",
            "attention required",
            "cf-chl",
            "cf_chl",
            "access denied", // Akamai
    };

    /**
     * Throws {@link EdgeChallengeException} at once if this response is an edge/bot challenge
     * rather than the page that was asked for. A no-op for a healthy response, so it is safe to
     * call on every fetch before parsing.
     */
    public static void check(Connection.Response response) {
        String mitigated = response.header("cf-mitigated");
        if (isMitigatedChallenge(mitigated)) {
            throw new EdgeChallengeException("Cloudflare JS challenge — not retryable");
        }

        int status = response.statusCode();
        if (status != 403 && status != 503) {
            return; // 429 is rate limiting; other statuses are handled by the caller
        }
        String marker = matchedBodyMarker(safeBody(response));
        if (marker != null) {
            throw new EdgeChallengeException(
                    "Edge/bot challenge (HTTP " + status + ", \"" + marker + "\") — not retryable");
        }
    }

    /**
     * Kept for the B&amp;H fetch loop and its tests: throws only on the {@code cf-mitigated}
     * header, without materialising the body. {@link #check} is the fuller entry point.
     */
    public static void failFastIfMitigated(Connection.Response response) {
        if (isMitigatedChallenge(response.header("cf-mitigated"))) {
            throw new EdgeChallengeException("Cloudflare JS challenge — not retryable");
        }
    }

    /**
     * Whether a {@code cf-mitigated} header value names a challenge. Split out so the decision
     * can be exercised without a live {@link Connection.Response}. Cloudflare sends the bare
     * token {@code challenge}; matched case-insensitively and as a substring so a compound
     * value ({@code challenge; ...}) is still caught.
     */
    static boolean isMitigatedChallenge(String cfMitigated) {
        return cfMitigated != null && cfMitigated.toLowerCase(Locale.ROOT).contains("challenge");
    }

    /** The interstitial marker present in this body, or null when none is — case-insensitive. */
    static String matchedBodyMarker(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        for (String marker : BODY_MARKERS) {
            if (lower.contains(marker)) {
                return marker;
            }
        }
        return null;
    }

    private static String safeBody(Connection.Response response) {
        try {
            return response.body();
        } catch (RuntimeException e) {
            return null; // nothing readable to judge on
        }
    }
}
