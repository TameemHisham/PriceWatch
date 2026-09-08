package com.tameem.pricewatch.scraper;

import com.tameem.pricewatch.config.MarketplaceRegistry;
import com.tameem.pricewatch.config.ScrapeProperties;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URL;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the real extraction against a saved copy of a live Flipkart product page
 * (src/test/resources/scraper/flipkart/product.html), without a network request.
 */
class FlipkartScraperTest {

    private static final String FIXTURE = "/scraper/flipkart/product.html";

    private static final String PRODUCT_URL =
            "https://www.flipkart.com/ai-nova-2-neo-5g-blue-128-gb/p/itmdcaa61dad2064";

    private static final String ITEM_ID = "itmdcaa61dad2064";

    private static ScrapeProperties.MarketplaceConfig flipkartConfig() {
        ScrapeProperties.MarketplaceConfig config = new ScrapeProperties.MarketplaceConfig();
        config.setHost("flipkart.com");
        config.setDeliveryCountry("IN");
        config.setAcceptLanguage("en-IN,en;q=0.9");
        return config;
    }

    private static FlipkartScraper scraper() {
        ScrapeProperties properties = new ScrapeProperties();
        properties.getMarketplaces().put("FLIPKART", flipkartConfig());
        return new FlipkartScraper(new MarketplaceRegistry(properties));
    }

    private static Document fixture() throws IOException {
        try (InputStream in = FlipkartScraperTest.class.getResourceAsStream(FIXTURE)) {
            assertNotNull(in, "Missing fixture on the test classpath: " + FIXTURE);
            return Jsoup.parse(in, "UTF-8", PRODUCT_URL);
        }
    }

    private static URL url(String value) {
        try {
            return URI.create(value).toURL();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ProductData parse(Document doc) {
        return scraper().parse(doc, url(PRODUCT_URL), PRODUCT_URL, flipkartConfig());
    }

    @Test
    void extractsTitleFromFixture() throws IOException {
        assertEquals("Ai+ Nova 2 Neo 5G (Blue, 128 GB) (4 GB RAM)", parse(fixture()).title());
    }

    @Test
    void extractsPriceCurrencyAndAvailabilityFromJsonLd() throws IOException {
        ProductData data = parse(fixture());
        assertEquals(0, new BigDecimal("15999").compareTo(data.price()),
                "expected the ld+json offer price, got " + data.price());
        assertEquals("INR", data.currency());
        assertEquals(Availability.AVAILABLE, data.availability());
        assertTrue(data.hasPrice());
    }

    @Test
    void extractsImage() throws IOException {
        assertNotNull(parse(fixture()).imageUrl());
    }

    /**
     * The reason this store is ld+json only. The page shows several rupee figures — list
     * price, discounted price, an EMI instalment — so a DOM text scan could record a monthly
     * instalment as the product price. With the offer gone it must fail, not guess.
     */
    @Test
    void throwsRatherThanReadingPriceFromDomWhenJsonLdMissing() throws IOException {
        Document doc = fixture();
        doc.select("script[type=application/ld+json]").remove();
        ScrapeException thrown = assertThrows(ScrapeException.class, () -> parse(doc));
        assertTrue(thrown.getMessage().contains("only read from ld+json"), thrown.getMessage());
    }

    @Test
    void throwsWhenJsonLdIsMalformed() throws IOException {
        Document doc = fixture();
        for (var script : doc.select("script[type=application/ld+json]")) {
            script.text("{ this is not json");
        }
        assertThrows(ScrapeException.class, () -> parse(doc));
    }

    /**
     * A healthy Flipkart page embeds a client-side error dictionary containing the literal
     * string "Access Denied" for its own 5xx screens. Treating that as a block would reject
     * every scrape, so the fixture must parse cleanly despite carrying it.
     */
    @Test
    void accessDeniedStringInPageScriptsIsNotTreatedAsABlock() throws IOException {
        Document doc = fixture();
        assertTrue(doc.html().contains("Access Denied"),
                "fixture should still carry the error-dictionary string");
        assertDoesNotThrow(() -> parse(doc));
    }

    @Test
    void rejectsBotChallengePage() {
        Document challenge = Jsoup.parse(
                "<html><body><div id=\"px-captcha\"></div></body></html>", PRODUCT_URL);
        ScrapeException thrown = assertThrows(ScrapeException.class, () ->
                scraper().parse(challenge, url(PRODUCT_URL), PRODUCT_URL, flipkartConfig()));
        assertTrue(thrown.getMessage().contains("bot challenge"), thrown.getMessage());
    }

    @Test
    void productKeyReadsListingIdFromUrl() {
        assertEquals(Optional.of(ITEM_ID), scraper().productKey(PRODUCT_URL));
        assertEquals(Optional.of(ITEM_ID),
                scraper().productKey(PRODUCT_URL + "?pid=MOBHZ8ZMTFJZHQQ6&lid=LSTxyz"));
    }

    /** The pid is a separate catalogue id from the listing id — both are read. */
    @Test
    void productIdReadsThePidQueryParameter() {
        assertEquals(Optional.of("MOBHZ8ZMTFJZHQQ6"),
                scraper().productId(PRODUCT_URL + "?pid=MOBHZ8ZMTFJZHQQ6&lid=LSTxyz"));
        assertEquals(Optional.empty(), scraper().productId(PRODUCT_URL));
    }

    @Test
    void productKeyIsEmptyForNonProductUrl() {
        assertEquals(Optional.empty(), scraper().productKey("https://www.flipkart.com/mobiles-store"));
    }

    @Test
    void canonicalUrlDropsQueryParameters() {
        assertEquals(PRODUCT_URL,
                scraper().canonicalUrl(PRODUCT_URL + "?pid=MOBHZ8ZMTFJZHQQ6&lid=LSTxyz&marketplace=FLIPKART"));
    }

    @Test
    void supportsOnlyItsOwnMarketplace() {
        assertTrue(scraper().supports("FLIPKART"));
        assertFalse(scraper().supports("IKEA"));
        assertFalse(scraper().supports("AMAZON_UK"));
    }

    @Test
    void rejectsPageAnsweredByAnotherHost() throws IOException {
        Document doc = fixture();
        ScrapeException thrown = assertThrows(ScrapeException.class, () ->
                scraper().parse(doc, url("https://www.example.com/whatever"), PRODUCT_URL, flipkartConfig()));
        assertTrue(thrown.getMessage().contains("redirected off the requested marketplace"),
                thrown.getMessage());
    }

    @Test
    void rejectsPageForADifferentItemId() throws IOException {
        Document doc = fixture();
        String other = "https://www.flipkart.com/other-thing/p/itmffffffffffffff";
        ScrapeException thrown = assertThrows(ScrapeException.class, () ->
                scraper().parse(doc, url(other), other, flipkartConfig()));
        assertTrue(thrown.getMessage().contains(ITEM_ID), thrown.getMessage());
    }

    @Test
    void rejectsPageWithoutAProductTitle() throws IOException {
        Document doc = fixture();
        doc.select("h1").remove();
        doc.select("script[type=application/ld+json]").remove();
        ScrapeException thrown = assertThrows(ScrapeException.class, () -> parse(doc));
        assertTrue(thrown.getMessage().contains("not a product page"), thrown.getMessage());
    }
}
