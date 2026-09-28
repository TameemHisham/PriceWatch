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

/** The shared Cloudflare fail-fast that every response-reading scraper leans on. */
class EdgeChallengeTest {

    private static Connection.Response responseWith(String cfMitigated) {
        Connection.Response response = mock(Connection.Response.class);
        when(response.header("cf-mitigated")).thenReturn(cfMitigated);
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
}
