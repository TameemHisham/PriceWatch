package com.tameem.pricewatch.scraper;

import com.tameem.pricewatch.config.MarketplaceRegistry;
import com.tameem.pricewatch.config.ScrapeProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * canonicalUrl's one job: every URL that names the same page must come back as the same
 * string, whichever route produced it.
 * <p>
 * The two routes that mattered wrote different strings. A user pastes a browser URL, which
 * carries "www."; sibling fan-out builds "https://" + the configured host + "/dp/" + ASIN,
 * which does not. trackProduct dedupes on an exact URL match, so it missed across the two and
 * the insert collided on (tracked_product_id, marketplace) — commit 648a477.
 */
class AmazonScraperTest {

    private static final String ASIN = "B0CMWJSQMB";

    private static ScrapeProperties.MarketplaceConfig config(String host, String country,
                                                             String language) {
        ScrapeProperties.MarketplaceConfig config = new ScrapeProperties.MarketplaceConfig();
        config.setHost(host);
        config.setDeliveryCountry(country);
        config.setAcceptLanguage(language);
        return config;
    }

    private static AmazonScraper scraper() {
        ScrapeProperties properties = new ScrapeProperties();
        properties.getMarketplaces().put("AMAZON_UK",
                config("amazon.co.uk", "GB", "en-GB,en;q=0.9"));
        properties.getMarketplaces().put("AMAZON_AE",
                config("amazon.ae", "AE", "en-AE,en;q=0.9"));
        properties.getMarketplaces().put("AMAZON_US",
                config("amazon.com", "US", "en-US,en;q=0.9"));
        return new AmazonScraper(new MarketplaceRegistry(properties) , new ScraperRateLimiter());
    }

    /** The exact pair that produced the duplicate insert. */
    @Test
    void aPastedUrlAndAFanOutUrlForTheSamePageCanonicaliseIdentically() {
        AmazonScraper scraper = scraper();

        String pasted = scraper.canonicalUrl(
                "https://www.amazon.co.uk/dp/" + ASIN + "?th=1&ref_=ast_sto_dp");
        String fanOut = scraper.canonicalUrl("https://amazon.co.uk/dp/" + ASIN);

        assertEquals(pasted, fanOut);
        assertEquals("https://www.amazon.co.uk/dp/" + ASIN, pasted);
    }

    /** The stored form is the one the rest of the scrapers already emit. */
    @Test
    void qualifiesTheHostWithWww() {
        assertEquals("https://www.amazon.co.uk/dp/" + ASIN,
                scraper().canonicalUrl("https://amazon.co.uk/dp/" + ASIN));
    }

    /** Host comes from configuration now, but must still name the storefront asked for. */
    @Test
    void keepsEachAmazonStorefrontDistinct() {
        AmazonScraper scraper = scraper();

        assertEquals("https://www.amazon.ae/dp/" + ASIN,
                scraper.canonicalUrl("https://www.amazon.ae/dp/" + ASIN));
        assertEquals("https://www.amazon.com/dp/" + ASIN,
                scraper.canonicalUrl("https://amazon.com/dp/" + ASIN));
    }

    /** Long product-name URLs are the common paste; they must reduce to the same string. */
    @Test
    void reducesAFullProductPathToTheCanonicalForm() {
        assertEquals("https://www.amazon.co.uk/dp/" + ASIN,
                scraper().canonicalUrl("https://www.amazon.co.uk/Sony-WH-1000XM5-Cancelling"
                        + "-Headphones-Bluetooth/dp/" + ASIN + "/ref=sr_1_3?crid=X&sr=8-3"));
    }

    /** Scheme is normalised too: it was previously echoed back from the input. */
    @Test
    void forcesHttps() {
        assertEquals("https://www.amazon.co.uk/dp/" + ASIN,
                scraper().canonicalUrl("http://amazon.co.uk/dp/" + ASIN));
    }

    /** No ASIN means nothing to canonicalise — the existing contract, unchanged. */
    @Test
    void rejectsAUrlWithNoAsin() {
        assertThrows(ScrapeException.class,
                () -> scraper().canonicalUrl("https://www.amazon.co.uk/gp/bestsellers"));
    }

    // ---- currency parsing ----

    /**
     * The regression: amazon.com served to an international visitor renders the price as the
     * ISO code in text ("GBP 53.94"), not a glyph. That used to fall through to UNKNOWN, so
     * the listing dropped out of every USD comparison and stored a bogus currency. Both forms
     * of every currency Amazon shows here must resolve.
     */
    @Test
    void resolvesCurrencyWhetherWrittenAsCodeOrGlyph() {
        // ISO code in text — the amazon.com Global-Store rendering that produced UNKNOWN.
        assertEquals("GBP", AmazonScraper.parseCurrency("GBP 53.94"));
        assertEquals("USD", AmazonScraper.parseCurrency("USD 229.00"));
        assertEquals("AED", AmazonScraper.parseCurrency("AED 1,724.76"));
        assertEquals("EUR", AmazonScraper.parseCurrency("EUR 19,99"));

        // Glyph — the amazon.co.uk rendering that already worked, and must keep working.
        assertEquals("GBP", AmazonScraper.parseCurrency("£31.99"));
        assertEquals("USD", AmazonScraper.parseCurrency("$229.00"));
        assertEquals("USD", AmazonScraper.parseCurrency("US$229.00"));
        assertEquals("EUR", AmazonScraper.parseCurrency("€19,99"));
        assertEquals("AED", AmazonScraper.parseCurrency("د.إ 100"));
    }

    /** A bare number with no currency marker is genuinely unknowable, and stays UNKNOWN. */
    @Test
    void reportsUnknownOnlyWhenThereIsNoCurrencyMarkerAtAll() {
        assertEquals("UNKNOWN", AmazonScraper.parseCurrency("229.00"));
        assertNull(AmazonScraper.parseCurrency(null));
    }
}
