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
 * Runs the real extraction against a saved copy of a live Jarir product page
 * (src/test/resources/scraper/jarir/product.html), without a network request.
 */
class JarirScraperTest {

    private static final String FIXTURE = "/scraper/jarir/product.html";

    private static final String PRODUCT_URL =
            "https://www.jarir.com/sa-en/prang-colors-and-coloring-set-27178.html";

    private static final String SKU = "27178";

    private static ScrapeProperties.MarketplaceConfig jarirConfig() {
        ScrapeProperties.MarketplaceConfig config = new ScrapeProperties.MarketplaceConfig();
        config.setHost("jarir.com");
        config.setDeliveryCountry("SA");
        config.setAcceptLanguage("en-SA,en;q=0.9");
        return config;
    }

    private static JarirScraper scraper() {
        ScrapeProperties properties = new ScrapeProperties();
        properties.getMarketplaces().put("JARIR", jarirConfig());
        return new JarirScraper(new MarketplaceRegistry(properties));
    }

    private static Document fixture() throws IOException {
        try (InputStream in = JarirScraperTest.class.getResourceAsStream(FIXTURE)) {
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
        return scraper().parse(doc, url(PRODUCT_URL), PRODUCT_URL, jarirConfig());
    }

    /** The rendered h1 is cleaner than the ld+json name, which carries a store suffix. */
    @Test
    void prefersRenderedTitleOverJsonLdName() throws IOException {
        assertEquals("Prang Watercolor", parse(fixture()).title());
    }

    @Test
    void extractsPriceCurrencyAndAvailabilityFromJsonLd() throws IOException {
        ProductData data = parse(fixture());
        assertEquals(0, new BigDecimal("39.00").compareTo(data.price()),
                "expected the ld+json offer price, got " + data.price());
        assertEquals("SAR", data.currency());
        assertEquals(Availability.AVAILABLE, data.availability());
        assertTrue(data.hasPrice());
    }

    @Test
    void extractsImage() throws IOException {
        assertEquals("https://ak-asset.jarir.com/akeneo-prod/asset/m1images/2/7/27178.jpg",
                parse(fixture()).imageUrl());
    }

    @Test
    void prefersJsonLdOverDomPriceBox() throws IOException {
        Document doc = fixture();
        doc.select(".price-box--pdp .price--pdp").forEach(el -> el.text("SR 1"));
        assertEquals(0, new BigDecimal("39.00").compareTo(parse(doc).price()),
                "DOM price must not override the ld+json offer");
    }

    /** With the offer gone, the rendered box is the fallback — and "SR" must map to SAR. */
    @Test
    void fallsBackToDomPriceBoxWhenJsonLdMissing() throws IOException {
        Document doc = fixture();
        doc.select("script[type=application/ld+json]").remove();
        ProductData data = parse(doc);
        assertEquals(0, new BigDecimal("39").compareTo(data.price()));
        assertEquals("SAR", data.currency());
        assertEquals(Availability.AVAILABLE, data.availability());
    }

    /**
     * A healthy Jarir page ships a PerimeterX sensor. Matching on that would fail every
     * scrape, so the fixture must parse cleanly despite it.
     */
    @Test
    void perimeterXSensorOnAHealthyPageIsNotTreatedAsABlock() throws IOException {
        Document doc = fixture();
        assertTrue(doc.html().contains("_px"), "fixture should still carry the PX sensor");
        assertDoesNotThrow(() -> parse(doc));
    }

    @Test
    void rejectsPerimeterXChallengePage() {
        Document challenge = Jsoup.parse(
                "<html><body><div id=\"px-captcha\"></div></body></html>", PRODUCT_URL);
        ScrapeException thrown = assertThrows(ScrapeException.class, () ->
                scraper().parse(challenge, url(PRODUCT_URL), PRODUCT_URL, jarirConfig()));
        assertTrue(thrown.getMessage().contains("bot challenge"), thrown.getMessage());
    }

    @Test
    void productKeyReadsSkuFromUrl() {
        assertEquals(Optional.of(SKU), scraper().productKey(PRODUCT_URL));
    }

    @Test
    void productKeyIsEmptyForNonProductUrl() {
        assertEquals(Optional.empty(), scraper().productKey("https://www.jarir.com/sa-en/laptops.html"));
    }

    /** Jarir 404s on a slugless URL, so the canonical form must keep the whole path. */
    @Test
    void canonicalUrlKeepsTheSlugAndDropsQueryParams() {
        assertEquals(PRODUCT_URL,
                scraper().canonicalUrl(PRODUCT_URL + "?utm_source=x&cid=7"));
    }

    @Test
    void canonicalUrlNormalisesHost() {
        assertEquals(PRODUCT_URL,
                scraper().canonicalUrl("https://jarir.com/sa-en/prang-colors-and-coloring-set-27178.html"));
    }

    @Test
    void supportsOnlyItsOwnMarketplace() {
        assertTrue(scraper().supports("JARIR"));
        assertFalse(scraper().supports("AMAZON_UK"));
        assertFalse(scraper().supports("CURRYS"));
    }

    @Test
    void rejectsPageAnsweredByAnotherHost() throws IOException {
        Document doc = fixture();
        ScrapeException thrown = assertThrows(ScrapeException.class, () ->
                scraper().parse(doc, url("https://www.example.com/whatever"), PRODUCT_URL, jarirConfig()));
        assertTrue(thrown.getMessage().contains("redirected off the requested marketplace"),
                thrown.getMessage());
    }

    @Test
    void rejectsPageForADifferentSku() throws IOException {
        Document doc = fixture();
        String other = "https://www.jarir.com/sa-en/something-else-99999.html";
        ScrapeException thrown = assertThrows(ScrapeException.class, () ->
                scraper().parse(doc, url(other), other, jarirConfig()));
        assertTrue(thrown.getMessage().contains(SKU), thrown.getMessage());
    }

    @Test
    void rejectsPageWithoutAProductTitle() throws IOException {
        Document doc = fixture();
        doc.select("h1, .product-title__title").remove();
        doc.select("script[type=application/ld+json]").remove();
        ScrapeException thrown = assertThrows(ScrapeException.class, () -> parse(doc));
        assertTrue(thrown.getMessage().contains("not a product page"), thrown.getMessage());
    }
}
