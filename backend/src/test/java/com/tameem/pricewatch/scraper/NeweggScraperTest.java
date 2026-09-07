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
 * Runs the real extraction against a saved copy of a live Newegg product page
 * (src/test/resources/scraper/newegg/product.html), so the selectors are exercised
 * without a network request.
 */
class NeweggScraperTest {

    private static final String FIXTURE = "/scraper/newegg/product.html";

    private static final String PRODUCT_URL =
            "https://www.newegg.com/amd-ryzen-7-9000-series-ryzen-7-9800x3d-granite-ridge-zen-5"
                    + "-socket-am5-desktop-cpu-processor/p/N82E16819113877";

    private static final String ITEM_NUMBER = "N82E16819113877";

    private static ScrapeProperties.MarketplaceConfig neweggConfig() {
        ScrapeProperties.MarketplaceConfig config = new ScrapeProperties.MarketplaceConfig();
        config.setHost("newegg.com");
        config.setDeliveryCountry("US");
        config.setAcceptLanguage("en-US,en;q=0.9");
        return config;
    }

    private static NeweggScraper scraper() {
        ScrapeProperties properties = new ScrapeProperties();
        properties.getMarketplaces().put("NEWEGG", neweggConfig());
        return new NeweggScraper(new MarketplaceRegistry(properties));
    }

    private static Document fixture() throws IOException {
        try (InputStream in = NeweggScraperTest.class.getResourceAsStream(FIXTURE)) {
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

    private static ProductData parseFixture() throws IOException {
        return scraper().parse(fixture(), url(PRODUCT_URL), PRODUCT_URL, neweggConfig());
    }

    @Test
    void extractsTitleFromFixture() throws IOException {
        String title = parseFixture().title();
        assertTrue(title.startsWith("AMD Ryzen 7 9800X3D"), "unexpected title: " + title);
        assertTrue(title.contains("100-100001084WOF"), "unexpected title: " + title);
    }

    @Test
    void extractsBuyBoxPriceNotStruckThroughPrice() throws IOException {
        ProductData data = parseFixture();
        // The page also renders a "was" price of $507.99 and combo prices of $464.00/$480.00.
        assertEquals(0, new BigDecimal("469.00").compareTo(data.price()),
                "expected the buy-box price, got " + data.price());
    }

    @Test
    void extractsCurrencyAndImageAndAvailability() throws IOException {
        ProductData data = parseFixture();
        assertEquals("USD", data.currency());
        assertEquals("https://c1.neweggimages.com/ProductImage/19-113-877-01.png", data.imageUrl());
        assertEquals(Availability.AVAILABLE, data.availability());
        assertTrue(data.hasPrice());
    }

    @Test
    void productKeyReadsItemNumberFromPath() {
        assertEquals(Optional.of(ITEM_NUMBER), scraper().productKey(PRODUCT_URL));
    }

    @Test
    void productKeyReadsItemNumberFromQueryParameter() {
        assertEquals(Optional.of(ITEM_NUMBER),
                scraper().productKey("https://www.newegg.com/x/p/N82E16819113877?Item=" + ITEM_NUMBER));
    }

    @Test
    void productKeyReadsMarketplaceItemNumber() {
        assertEquals(Optional.of("9SIC0X3KG23952"),
                scraper().productKey("https://www.newegg.com/x/p/9SIC0X3KG23952"));
    }

    @Test
    void productKeyIsEmptyForNonProductUrl() {
        assertEquals(Optional.empty(), scraper().productKey("https://www.newegg.com/Desktop-CPU/Category/ID-34"));
    }

    @Test
    void rejectsPageAnsweredByAnotherHost() throws IOException {
        Document doc = fixture();
        ScrapeException thrown = assertThrows(ScrapeException.class, () ->
                scraper().parse(doc, url("https://www.example.com/whatever"), PRODUCT_URL, neweggConfig()));
        assertTrue(thrown.getMessage().contains("redirected off the requested marketplace"),
                thrown.getMessage());
    }

    @Test
    void rejectsPageForADifferentItemNumber() throws IOException {
        Document doc = fixture();
        String otherItem = "https://www.newegg.com/some-other-product/p/N82E16813144743";
        ScrapeException thrown = assertThrows(ScrapeException.class, () ->
                scraper().parse(doc, url(otherItem), otherItem, neweggConfig()));
        assertTrue(thrown.getMessage().contains(ITEM_NUMBER), thrown.getMessage());
    }

    @Test
    void rejectsPageWithoutAProductTitle() throws IOException {
        Document doc = fixture();
        doc.select("h1.product-title").remove();
        ScrapeException thrown = assertThrows(ScrapeException.class, () ->
                scraper().parse(doc, url(PRODUCT_URL), PRODUCT_URL, neweggConfig()));
        assertTrue(thrown.getMessage().contains("not a product page"), thrown.getMessage());
    }

    @Test
    void rejectsBotChallengePage() {
        Document challenge = Jsoup.parse(
                "<html><body><div id=\"sec-if-cpt-container\"></div></body></html>", PRODUCT_URL);
        ScrapeException thrown = assertThrows(ScrapeException.class, () ->
                scraper().parse(challenge, url(PRODUCT_URL), PRODUCT_URL, neweggConfig()));
        assertTrue(thrown.getMessage().contains("bot challenge"), thrown.getMessage());
    }
}
