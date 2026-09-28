package com.tameem.pricewatch.scraper;

import org.jsoup.Connection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The shared edge-challenge detection every response-reading scraper leans on. */
class EdgeChallengeTest {

    private static Connection.Response responseWith(String cfMitigated) {
        Connection.Response response = mock(Connection.Response.class);
        when(response.header("cf-mitigated")).thenReturn(cfMitigated);
        return response;
    }

    private static Connection.Response responseWith(String cfMitigated, int status, String body) {
        Connection.Response response = mock(Connection.Response.class);
        when(response.header("cf-mitigated")).thenReturn(cfMitigated);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    @Test
    void throwsWithAClearMessageOnAMitigatedChallenge() {
        ScrapeException thrown = assertThrows(ScrapeException.class,
                () -> EdgeChallenge.failFastIfMitigated(responseWith("challenge")));
        assertEquals("Cloudflare JS challenge — not retryable", thrown.getMessage());
    }

    @Test
    void ignoresAResponseWithoutTheHeader() {
        assertDoesNotThrow(() -> EdgeChallenge.failFastIfMitigated(responseWith(null)));
    }

    @Test
    void doesNotFireOnAnUnrelatedMitigation() {
        assertFalse(EdgeChallenge.isMitigatedChallenge("rate_limited"));
        assertFalse(EdgeChallenge.isMitigatedChallenge(null));
        assertFalse(EdgeChallenge.isMitigatedChallenge(""));
    }

    @Test
    void recognisesTheChallengeTokenCaseInsensitivelyAndInCompoundValues() {
        assertTrue(EdgeChallenge.isMitigatedChallenge("challenge"));
        assertTrue(EdgeChallenge.isMitigatedChallenge("CHALLENGE"));
        assertTrue(EdgeChallenge.isMitigatedChallenge("challenge; source=bot-management"));
    }

    // ---- check(response): header + status/body markers ----

    @Test
    void checkThrowsOnTheMitigatedHeaderRegardlessOfStatus() {
        EdgeChallengeException thrown = assertThrows(EdgeChallengeException.class,
                () -> EdgeChallenge.check(responseWith("challenge", 200, "")));
        assertEquals("Cloudflare JS challenge — not retryable", thrown.getMessage());
    }

    @Test
    void checkThrowsOnA403WithACloudflareInterstitial() {
        assertThrows(EdgeChallengeException.class, () -> EdgeChallenge.check(
                responseWith(null, 403, "<html><head><title>Just a moment...</title></head></html>")));
    }

    @Test
    void checkThrowsOnA503WithAnAttentionRequiredPage() {
        assertThrows(EdgeChallengeException.class, () -> EdgeChallenge.check(
                responseWith(null, 503, "<h1>Attention Required! | Cloudflare</h1>")));
    }

    @Test
    void checkThrowsOnAkamaiAccessDenied() {
        assertThrows(EdgeChallengeException.class, () -> EdgeChallenge.check(
                responseWith(null, 403, "<html><body>Access Denied</body></html>")));
    }

    @Test
    void checkThrowsOnACfChlBody() {
        assertThrows(EdgeChallengeException.class, () -> EdgeChallenge.check(
                responseWith(null, 403, "<script>window._cf_chl_opt = {}</script>")));
    }

    @Test
    void checkDoesNotThrowOnAPlain403WithNoInterstitial() {
        assertDoesNotThrow(() -> EdgeChallenge.check(
                responseWith(null, 403, "<html><body>Not found</body></html>")));
    }

    @Test
    void checkDoesNotThrowOnAHealthy200() {
        assertDoesNotThrow(() -> EdgeChallenge.check(
                responseWith(null, 200, "<html><head><title>Sony WH-1000XM5</title></head></html>")));
    }

    @Test
    void markerMatchIsCaseInsensitiveAndAbsentOnACleanPage() {
        assertEquals("just a moment", EdgeChallenge.matchedBodyMarker("<title>JUST A MOMENT...</title>"));
        assertEquals("access denied", EdgeChallenge.matchedBodyMarker("ACCESS DENIED"));
        assertEquals(null, EdgeChallenge.matchedBodyMarker("<title>Real Product Page</title>"));
        assertEquals(null, EdgeChallenge.matchedBodyMarker(null));
    }
}
