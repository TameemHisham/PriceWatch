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
 * Runs the real extraction against a saved copy of a live eXtra product page
 * (src/test/resources/scraper/extra/product.html), without a network request.
 */
class ExtraScraperTest {

    private static final String FIXTURE = "/scraper/extra/product.html";

    private static final String PRODUCT_URL =
            "https://www.extra.com/en-sa/mobiles-tablets/wearable/smart-watches/"
                    + "apple-watch-se-gps-40mm-silver-aluminium-case-with-denim-sport-band-m-l/p/100383287";

    private static final String SKU = "100383287";

    private static ScrapeProperties.MarketplaceConfig extraConfig() {
        ScrapeProperties.MarketplaceConfig config = new ScrapeProperties.MarketplaceConfig();
        config.setHost("extra.com");
        config.setDeliveryCountry("SA");
        config.setAcceptLanguage("en-SA,en;q=0.9");
        return config;
    }

    private static ExtraScraper scraper() {
        ScrapeProperties properties = new ScrapeProperties();
        properties.getMarketplaces().put("EXTRA", extraConfig());
        return new ExtraScraper(new MarketplaceRegistry(properties));
    }

    private static Document fixture() throws IOException {
        try (InputStream in = ExtraScraperTest.class.getResourceAsStream(FIXTURE)) {
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
        return scraper().parse(doc, url(PRODUCT_URL), PRODUCT_URL, extraConfig());
    }

    /** The only h1 is a hidden SEO heading that appends " - eXtra"; ld+json is the clean one. */
    @Test
    void prefersJsonLdNameOverTheSiteSuffixedH1() throws IOException {
        String title = parse(fixture()).title();
        assertEquals("Apple Watch SE GPS 40MM Silver Aluminium Case with Denim Sport Band - M/L", title);
        assertFalse(title.endsWith("- eXtra"), "site name must not leak into the product title");
    }

    /** With no ld+json name, the h1 is still better than nothing. */
    @Test
    void fallsBackToTheH1WhenJsonLdHasNoName() throws IOException {
        Document doc = fixture();
        for (var script : doc.select("script[type=application/ld+json]")) {
            script.text(script.data().replace("\"name\"", "\"nameRemoved\""));
        }
        assertTrue(parse(doc).title().startsWith("Apple Watch SE GPS 40MM"));
    }

    @Test
    void extractsPriceCurrencyAndAvailabilityFromJsonLd() throws IOException {
        ProductData data = parse(fixture());
        assertEquals(0, new BigDecimal("550").compareTo(data.price()),
                "expected the ld+json offer price, got " + data.price());
        assertEquals("SAR", data.currency());
        assertEquals(Availability.AVAILABLE, data.availability());
        assertTrue(data.hasPrice());
    }

    @Test
    void extractsImage() throws IOException {
        assertNotNull(parse(fixture()).imageUrl());
    }

    /**
     * The reason this store is ld+json only: the served markup contains no price at all —
     * not as text, not in a data attribute — so there is nothing to fall back to.
     */
    @Test
    void servedMarkupCarriesNoPriceToFallBackTo() throws IOException {
        Document doc = fixture();
        doc.select("script, style").remove();
        assertFalse(doc.text().contains("550"),
                "if a price ever appears in the DOM, a fallback becomes worth adding");
    }

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

    @Test
    void productKeyReadsNumericSku() {
        assertEquals(Optional.of(SKU), scraper().productKey(PRODUCT_URL));
    }

    /** Marketplace-seller SKUs use an "MP" prefix rather than a plain number. */
    @Test
    void productKeyReadsMarketplaceSellerSku() {
        assertEquals(Optional.of("MP00023798"),
                scraper().productKey("https://www.extra.com/en-sa/pet-supplies/food/wet-food/x/p/MP00023798"));
    }

    @Test
    void productKeyIsEmptyForNonProductUrl() {
        assertEquals(Optional.empty(),
                scraper().productKey("https://www.extra.com/en-sa/mobiles-tablets/mobiles/c/2-212"));
    }

    /** eXtra resolves a product from its SKU alone, so the category path is dropped. */
    @Test
    void canonicalUrlDropsCategoryPathButKeepsLocale() {
        assertEquals("https://www.extra.com/en-sa/p/100383287",
                scraper().canonicalUrl(PRODUCT_URL));
    }

    /** Locale sets the currency, so it must survive canonicalisation. */
    @Test
    void canonicalUrlPreservesADifferentLocale() {
        assertEquals("https://www.extra.com/en-bh/p/100383287",
                scraper().canonicalUrl("https://www.extra.com/en-bh/some/category/p/100383287"));
    }

    @Test
    void supportsOnlyItsOwnMarketplace() {
        assertTrue(scraper().supports("EXTRA"));
        assertFalse(scraper().supports("JARIR"));
        assertFalse(scraper().supports("FLIPKART"));
    }

    @Test
    void rejectsPageAnsweredByAnotherHost() throws IOException {
        Document doc = fixture();
        ScrapeException thrown = assertThrows(ScrapeException.class, () ->
                scraper().parse(doc, url("https://www.example.com/whatever"), PRODUCT_URL, extraConfig()));
        assertTrue(thrown.getMessage().contains("redirected off the requested marketplace"),
                thrown.getMessage());
    }

    @Test
    void rejectsPageForADifferentSku() throws IOException {
        Document doc = fixture();
        String other = "https://www.extra.com/en-sa/p/999999999";
        ScrapeException thrown = assertThrows(ScrapeException.class, () ->
                scraper().parse(doc, url(other), other, extraConfig()));
        assertTrue(thrown.getMessage().contains(SKU), thrown.getMessage());
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
                "<html><body><div id=\"px-captcha\"></div></body></html>", PRODUCT_URL);
        ScrapeException thrown = assertThrows(ScrapeException.class, () ->
                scraper().parse(challenge, url(PRODUCT_URL), PRODUCT_URL, extraConfig()));
        assertTrue(thrown.getMessage().contains("bot challenge"), thrown.getMessage());
    }
}
