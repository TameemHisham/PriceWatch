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
 * Runs the real extraction against a saved copy of a live IKEA product page
 * (src/test/resources/scraper/ikea/product.html), without a network request.
 */
class IkeaScraperTest {

    private static final String FIXTURE = "/scraper/ikea/product.html";

    private static final String PRODUCT_URL =
            "https://www.ikea.com/us/en/p/aengslilja-duvet-cover-and-pillowcase-s-blue-gray-40585226/";

    private static final String ARTICLE = "40585226";

    private static ScrapeProperties.MarketplaceConfig ikeaConfig() {
        ScrapeProperties.MarketplaceConfig config = new ScrapeProperties.MarketplaceConfig();
        config.setHost("ikea.com");
        config.setDeliveryCountry("US");
        config.setAcceptLanguage("en-US,en;q=0.9");
        return config;
    }

    private static IkeaScraper scraper() {
        ScrapeProperties properties = new ScrapeProperties();
        properties.getMarketplaces().put("IKEA", ikeaConfig());
        return new IkeaScraper(new MarketplaceRegistry(properties));
    }

    private static Document fixture() throws IOException {
        try (InputStream in = IkeaScraperTest.class.getResourceAsStream(FIXTURE)) {
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
        return scraper().parse(doc, url(PRODUCT_URL), PRODUCT_URL, ikeaConfig());
    }

    @Test
    void extractsTitleFromFixture() throws IOException {
        String title = parse(fixture()).title();
        assertTrue(title.startsWith("ÄNGSLILJA"), "unexpected title: " + title);
    }

    @Test
    void extractsPriceCurrencyAndAvailabilityFromJsonLd() throws IOException {
        ProductData data = parse(fixture());
        assertEquals(0, new BigDecimal("39.99").compareTo(data.price()),
                "expected the ld+json offer price, got " + data.price());
        assertEquals("USD", data.currency());
        assertEquals(Availability.AVAILABLE, data.availability());
        assertTrue(data.hasPrice());
    }

    @Test
    void extractsImage() throws IOException {
        assertNotNull(parse(fixture()).imageUrl());
    }

    @Test
    void prefersJsonLdOverRenderedPrice() throws IOException {
        Document doc = fixture();
        doc.select(".pipcom-price__sr-text").forEach(el -> el.text("Price $ 1.00"));
        doc.select("[data-product-price]").forEach(el -> el.attr("data-product-price", "1.00"));
        assertEquals(0, new BigDecimal("39.99").compareTo(parse(doc).price()),
                "DOM price must not override the ld+json offer");
    }

    /** The screen-reader span is the fallback; it holds the whole price as one string. */
    @Test
    void fallsBackToTheScreenReaderPriceWhenJsonLdMissing() throws IOException {
        Document doc = fixture();
        doc.select("script[type=application/ld+json]").remove();
        ProductData data = parse(doc);
        assertEquals(0, new BigDecimal("39.99").compareTo(data.price()));
        assertEquals("USD", data.currency());
        assertEquals(Availability.AVAILABLE, data.availability());
    }

    /** With both ld+json and the price spans gone, the container attribute still carries it. */
    @Test
    void fallsBackToTheDataProductPriceAttribute() throws IOException {
        Document doc = fixture();
        doc.select("script[type=application/ld+json]").remove();
        doc.select(".pipcom-price__sr-text").remove();
        assertEquals(0, new BigDecimal("39.99").compareTo(parse(doc).price()));
    }

    @Test
    void productKeyReadsArticleNumberFromUrl() {
        assertEquals(Optional.of(ARTICLE), scraper().productKey(PRODUCT_URL));
    }

    @Test
    void productKeyReadsArticleNumberFromSluglessUrl() {
        assertEquals(Optional.of(ARTICLE),
                scraper().productKey("https://www.ikea.com/us/en/p/-40585226/"));
    }

    /**
     * Series/combination products prefix the article number with "s". Capturing the digits
     * only, because the page states them undotted in data-product-no and the identity check
     * compares against that — capturing "s99513930" would trade a parse failure for a
     * spurious "wrong article" rejection.
     */
    @Test
    void productKeyReadsAnSPrefixedSeriesArticleNumber() {
        assertEquals(Optional.of("99513930"), scraper().productKey(
                "https://www.ikea.com/ae/en/p/mittzon-conference-table-round-birch-veneer-white-s99513930/"));
    }

    @Test
    void productKeyReadsAnSPrefixedArticleFromASluglessUrl() {
        assertEquals(Optional.of("99513930"),
                scraper().productKey("https://www.ikea.com/ae/en/p/-s99513930/"));
    }

    /** Both /-99513930/ and /-s99513930/ resolve upstream, so the canonical form drops it. */
    @Test
    void canonicalUrlOfAnSPrefixedArticleKeepsItsLocaleAndDropsThePrefix() {
        assertEquals("https://www.ikea.com/ae/en/p/-99513930/", scraper().canonicalUrl(
                "https://www.ikea.com/ae/en/p/mittzon-conference-table-round-birch-veneer-white-s99513930/"));
    }

    @Test
    void productKeyIsEmptyForNonProductUrl() {
        assertEquals(Optional.empty(),
                scraper().productKey("https://www.ikea.com/us/en/cat/duvet-covers-10680/"));
    }

    /** IKEA resolves a product from the article number alone, so the slug can be dropped. */
    @Test
    void canonicalUrlDropsSlugButKeepsLocale() {
        assertEquals("https://www.ikea.com/us/en/p/-40585226/",
                scraper().canonicalUrl(PRODUCT_URL));
    }

    /** Locale lives in the path, not the host, so it must survive canonicalisation. */
    @Test
    void canonicalUrlPreservesADifferentLocale() {
        assertEquals("https://www.ikea.com/gb/en/p/-40585226/",
                scraper().canonicalUrl("https://www.ikea.com/gb/en/p/aengslilja-something-40585226/"));
    }

    @Test
    void supportsOnlyItsOwnMarketplace() {
        assertTrue(scraper().supports("IKEA"));
        assertFalse(scraper().supports("AMAZON_UK"));
        assertFalse(scraper().supports("JARIR"));
    }

    @Test
    void rejectsPageAnsweredByAnotherHost() throws IOException {
        Document doc = fixture();
        ScrapeException thrown = assertThrows(ScrapeException.class, () ->
                scraper().parse(doc, url("https://www.example.com/whatever"), PRODUCT_URL, ikeaConfig()));
        assertTrue(thrown.getMessage().contains("redirected off the requested marketplace"),
                thrown.getMessage());
    }

    /** The page publishes the article dotted in ld+json and undotted in data attributes. */
    @Test
    void rejectsPageForADifferentArticleNumber() throws IOException {
        Document doc = fixture();
        String other = "https://www.ikea.com/us/en/p/-12345678/";
        ScrapeException thrown = assertThrows(ScrapeException.class, () ->
                scraper().parse(doc, url(other), other, ikeaConfig()));
        assertTrue(thrown.getMessage().contains(ARTICLE), thrown.getMessage());
    }

    @Test
    void rejectsPageWithoutAProductTitle() throws IOException {
        Document doc = fixture();
        doc.select("h1").remove();
        doc.select("script[type=application/ld+json]").remove();
        ScrapeException thrown = assertThrows(ScrapeException.class, () -> parse(doc));
        assertTrue(thrown.getMessage().contains("not a product page"), thrown.getMessage());
    }

    @Test
    void rejectsBotChallengePage() {
        Document challenge = Jsoup.parse(
                "<html><body><div id=\"sec-if-cpt-container\"></div></body></html>", PRODUCT_URL);
        ScrapeException thrown = assertThrows(ScrapeException.class, () ->
                scraper().parse(challenge, url(PRODUCT_URL), PRODUCT_URL, ikeaConfig()));
        assertTrue(thrown.getMessage().contains("bot challenge"), thrown.getMessage());
    }
}
