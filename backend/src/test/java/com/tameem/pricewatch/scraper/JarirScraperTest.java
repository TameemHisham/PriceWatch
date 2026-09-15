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
import java.nio.charset.StandardCharsets;
import java.util.List;
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
        config.setSearchKey("key_test");
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

    // ---- search ----

    /**
     * Jarir's results page is a client-rendered shell with no product links, so search goes
     * to the hosted search provider its own search box queries. This is a saved response
     * from it, for the same product the page fixture is of.
     */
    private static String searchFixture() throws IOException {
        try (InputStream in = JarirScraperTest.class.getResourceAsStream("/scraper/jarir/search.json")) {
            assertNotNull(in, "Missing search fixture on the test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<SearchResult> searchResults() throws IOException {
        return scraper().parseSearchResults(searchFixture(), jarirConfig());
    }

    /**
     * The provider answers with the slug alone. Rebuilding the URL from the configured host
     * and locale is what makes a hit land on the same storefront the product fetch uses.
     */
    @Test
    void parsesSearchResultsFromFixture() throws IOException {
        List<SearchResult> results = searchResults();
        assertFalse(results.isEmpty(), "expected hits from the saved search response");
        assertTrue(results.stream().anyMatch(r -> PRODUCT_URL.equals(r.url())),
                "the fixture's own product was not among the hits: " + results);
    }

    /** Every hit must be a product URL this scraper can go on to scrape. */
    @Test
    void everySearchResultCarriesATitleAndAProductUrl() throws IOException {
        for (SearchResult r : searchResults()) {
            assertFalse(r.title().isBlank(), "blank title in results");
            assertTrue(r.url().startsWith("https://www.jarir.com/sa-en/"), "wrong storefront: " + r.url());
            assertTrue(scraper().productKey(r.url()).isPresent(), "not a product URL: " + r.url());
        }
    }

    @Test
    void searchResultsAreNotDuplicatedPerProduct() throws IOException {
        List<SearchResult> results = searchResults();
        assertEquals(results.size(), results.stream().map(SearchResult::url).distinct().count(),
                "duplicate product URLs in results");
    }

    /** One track costs 1 + this many LLM calls on this store alone. */
    @Test
    void searchResultsAreCappedForQuota() throws IOException {
        assertTrue(searchResults().size() <= 3);
    }

    @Test
    void searchIgnoresABlankQueryWithoutFetching() {
        assertEquals(List.of(), scraper().search("   "));
    }

    /**
     * "Not configured" is a different fact from "found nothing", and the discovery loop logs
     * the one and silently accepts the other.
     */
    @Test
    void searchFailsLoudlyWhenNoSearchKeyIsConfigured() {
        ScrapeProperties.MarketplaceConfig keyless = jarirConfig();
        keyless.setSearchKey(null);
        ScrapeProperties properties = new ScrapeProperties();
        properties.getMarketplaces().put("JARIR", keyless);
        JarirScraper scraper = new JarirScraper(new MarketplaceRegistry(properties));

        ScrapeException thrown = assertThrows(ScrapeException.class, () -> scraper.search("prang watercolor"));
        assertTrue(thrown.getMessage().contains("search key"), thrown.getMessage());
    }

    @Test
    void reportsAnUnparseableSearchResponseRatherThanNoHits() {
        ScrapeException thrown = assertThrows(ScrapeException.class,
                () -> scraper().parseSearchResults("not json at all", jarirConfig()));
        assertTrue(thrown.getMessage().contains("Unparseable"), thrown.getMessage());
    }

    /**
     * The point of the whole change: cross-store discovery iterates only the searchable
     * scrapers, so without this Jarir can be tracked from a pasted URL but never found.
     */
    @Test
    void isDiscoverableAsASearchableScraper() {
        assertInstanceOf(SearchableScraper.class, scraper());
    }

    /**
     * The provider matches tokens, not meaning: a whole retailer title matches nothing at
     * all, so the query is narrowed to its leading words.
     */
    @Test
    void narrowsATitleToItsLeadingWords() {
        String title = "soundcore by Anker Q20i Hybrid ANC Foldable Headphones, 40H";
        assertEquals("soundcore by Anker Q20i", JarirScraper.firstWords(title, 4));
        assertEquals("soundcore by Anker", JarirScraper.firstWords(title, 3));
        assertEquals("soundcore by", JarirScraper.firstWords(title, 2));
    }

    /** A word boundary that lands on a separator would be sent as a token of its own. */
    @Test
    void narrowingDropsATrailingSeparator() {
        assertEquals("Ninja 2-In-1 Professional Blender",
                JarirScraper.firstWords("Ninja 2-In-1 Professional Blender, One Touch Blending", 4));
        assertEquals("Sony WH-1000XM5 Noise Cancelling",
                JarirScraper.firstWords("Sony  WH-1000XM5   Noise Cancelling Wireless Headphones", 4));
    }

    /** A title shorter than the widest rung narrows to itself, and must not loop on it. */
    @Test
    void narrowingAShortTitleYieldsTheWholeTitle() {
        assertEquals("Prang Watercolor", JarirScraper.firstWords("Prang Watercolor", 4));
        assertEquals("Prang Watercolor", JarirScraper.firstWords("Prang Watercolor", 2));
    }
}
