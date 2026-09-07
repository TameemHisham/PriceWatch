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
 * Runs the real extraction against a saved copy of a live B&amp;H product page
 * (src/test/resources/scraper/bhphoto/product.html), so the selectors and the JSON-LD path
 * are exercised without a network request.
 */
class BhPhotoScraperTest {

    private static final String FIXTURE = "/scraper/bhphoto/product.html";

    private static final String PRODUCT_URL =
            "https://www.bhphotovideo.com/c/product/1997421-REG/"
                    + "fujifilm_16981452_fujinon_400mm_f_4_5_r.html";

    private static final String ITEM_NUMBER = "1997421";

    private static ScrapeProperties.MarketplaceConfig bhConfig() {
        ScrapeProperties.MarketplaceConfig config = new ScrapeProperties.MarketplaceConfig();
        config.setHost("bhphotovideo.com");
        config.setDeliveryCountry("US");
        config.setAcceptLanguage("en-US,en;q=0.9");
        return config;
    }

    private static BhPhotoScraper scraper() {
        ScrapeProperties properties = new ScrapeProperties();
        properties.getMarketplaces().put("BH_PHOTO", bhConfig());
        return new BhPhotoScraper(new MarketplaceRegistry(properties));
    }

    private static Document fixture() throws IOException {
        try (InputStream in = BhPhotoScraperTest.class.getResourceAsStream(FIXTURE)) {
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
        return scraper().parse(doc, url(PRODUCT_URL), PRODUCT_URL, bhConfig());
    }

    @Test
    void extractsTitleFromFixture() throws IOException {
        assertEquals("FUJIFILM XF 400mm f/4.5 R LM OIS WR Lens", parse(fixture()).title());
    }

    @Test
    void extractsPriceAndCurrencyFromJsonLd() throws IOException {
        ProductData data = parse(fixture());
        assertEquals(0, new BigDecimal("3199.00").compareTo(data.price()),
                "expected the ld+json offer price, got " + data.price());
        assertEquals("USD", data.currency());
    }

    /** The fixture's offer is schema.org/PreOrder — a real, purchasable price. */
    @Test
    void treatsPreOrderOfferAsAvailable() throws IOException {
        ProductData data = parse(fixture());
        assertEquals(Availability.AVAILABLE, data.availability());
        assertTrue(data.hasPrice());
    }

    @Test
    void extractsImage() throws IOException {
        assertEquals("https://www.bhphotovideo.com/images/fb/"
                        + "fujifilm_16981452_fujinon_400mm_f_4_5_r_1997421.jpg",
                parse(fixture()).imageUrl());
    }

    /** JSON-LD wins over the rendered element when the two disagree. */
    @Test
    void prefersJsonLdOverDomPriceElement() throws IOException {
        Document doc = fixture();
        doc.select("[data-selenium=pricingPrice]").first().text("$1.00");
        assertEquals(0, new BigDecimal("3199.00").compareTo(parse(doc).price()),
                "DOM price must not override the ld+json offer");
    }

    /** With JSON-LD gone, the data-selenium hook is the fallback. */
    @Test
    void fallsBackToDomPriceWhenJsonLdMissing() throws IOException {
        Document doc = fixture();
        doc.select("script[type=application/ld+json]").remove();
        ProductData data = parse(doc);
        assertEquals(0, new BigDecimal("3199.00").compareTo(data.price()));
        assertEquals("USD", data.currency());
        assertEquals(Availability.AVAILABLE, data.availability());
    }

    /** A malformed block must not abort extraction — the selector chain still runs. */
    @Test
    void fallsBackToDomPriceWhenJsonLdIsMalformed() throws IOException {
        Document doc = fixture();
        doc.select("script[type=application/ld+json]").first().text("{ this is not json");
        assertEquals(0, new BigDecimal("3199.00").compareTo(parse(doc).price()));
    }

    @Test
    void productKeyReadsItemNumberFromUrl() {
        assertEquals(Optional.of(ITEM_NUMBER), scraper().productKey(PRODUCT_URL));
    }

    @Test
    void productKeyIsEmptyForNonProductUrl() {
        assertEquals(Optional.empty(),
                scraper().productKey("https://www.bhphotovideo.com/c/buy/Lenses/ci/274/N/4288586282"));
    }

    @Test
    void canonicalUrlDropsSlugButStaysFetchable() {
        assertEquals("https://www.bhphotovideo.com/c/product/1997421-REG/",
                scraper().canonicalUrl(PRODUCT_URL));
    }

    @Test
    void canonicalUrlPreservesConditionCode() {
        assertEquals("https://www.bhphotovideo.com/c/product/1997421-GRY/",
                scraper().canonicalUrl("https://www.bhphotovideo.com/c/product/1997421-GRY/x.html"));
    }

    @Test
    void supportsOnlyItsOwnMarketplace() {
        assertTrue(scraper().supports("BH_PHOTO"));
        assertFalse(scraper().supports("AMAZON_UK"));
        assertFalse(scraper().supports("NEWEGG"));
    }

    @Test
    void rejectsPageAnsweredByAnotherHost() throws IOException {
        Document doc = fixture();
        ScrapeException thrown = assertThrows(ScrapeException.class, () ->
                scraper().parse(doc, url("https://www.example.com/whatever"), PRODUCT_URL, bhConfig()));
        assertTrue(thrown.getMessage().contains("redirected off the requested marketplace"),
                thrown.getMessage());
    }

    @Test
    void rejectsPageForADifferentItemNumber() throws IOException {
        Document doc = fixture();
        String other = "https://www.bhphotovideo.com/c/product/1234567-REG/other.html";
        ScrapeException thrown = assertThrows(ScrapeException.class, () ->
                scraper().parse(doc, url(other), other, bhConfig()));
        assertTrue(thrown.getMessage().contains(ITEM_NUMBER), thrown.getMessage());
    }

    @Test
    void rejectsPageWithoutAProductTitle() throws IOException {
        Document doc = fixture();
        doc.select("[data-selenium=productTitle]").remove();
        doc.select("script[type=application/ld+json]").remove();
        ScrapeException thrown = assertThrows(ScrapeException.class, () ->
                parse(doc));
        assertTrue(thrown.getMessage().contains("not a product page"), thrown.getMessage());
    }

    @Test
    void rejectsBotChallengePage() {
        Document challenge = Jsoup.parse(
                "<html><body><div id=\"sec-if-cpt-container\"></div></body></html>", PRODUCT_URL);
        ScrapeException thrown = assertThrows(ScrapeException.class, () ->
                scraper().parse(challenge, url(PRODUCT_URL), PRODUCT_URL, bhConfig()));
        assertTrue(thrown.getMessage().contains("bot challenge"), thrown.getMessage());
    }
}
