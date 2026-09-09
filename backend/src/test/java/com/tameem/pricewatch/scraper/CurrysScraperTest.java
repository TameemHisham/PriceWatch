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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the real extraction against a saved copy of a live Currys product page
 * (src/test/resources/scraper/currys/product.html), without a network request.
 */
class CurrysScraperTest {

    private static final String FIXTURE = "/scraper/currys/product.html";

    private static final String PRODUCT_URL =
            "https://www.currys.co.uk/products/asus-vivobook-s16-oled-16-laptop-copilot-pc"
                    + "-intel-core-ultra-5-512-gb-ssd-matte-grey-10305211.html";

    private static final String SKU = "10305211";

    private static ScrapeProperties.MarketplaceConfig currysConfig() {
        ScrapeProperties.MarketplaceConfig config = new ScrapeProperties.MarketplaceConfig();
        config.setHost("currys.co.uk");
        config.setDeliveryCountry("GB");
        config.setAcceptLanguage("en-GB,en;q=0.9");
        return config;
    }

    private static CurrysScraper scraper() {
        ScrapeProperties properties = new ScrapeProperties();
        properties.getMarketplaces().put("CURRYS", currysConfig());
        return new CurrysScraper(new MarketplaceRegistry(properties));
    }

    private static Document fixture() throws IOException {
        try (InputStream in = CurrysScraperTest.class.getResourceAsStream(FIXTURE)) {
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
        return scraper().parse(doc, url(PRODUCT_URL), PRODUCT_URL, currysConfig());
    }

    @Test
    void extractsTitleFromFixture() throws IOException {
        String title = parse(fixture()).title();
        assertTrue(title.startsWith("ASUS Vivobook S16 OLED"), "unexpected title: " + title);
    }

    @Test
    void extractsPriceCurrencyAndAvailabilityFromJsonLd() throws IOException {
        ProductData data = parse(fixture());
        assertEquals(0, new BigDecimal("999.00").compareTo(data.price()),
                "expected the ld+json offer price, got " + data.price());
        assertEquals("GBP", data.currency());
        assertEquals(Availability.AVAILABLE, data.availability());
        assertTrue(data.hasPrice());
    }

    @Test
    void extractsImage() throws IOException {
        String image = parse(fixture()).imageUrl();
        assertNotNull(image);
        assertTrue(image.contains("currysprod/" + SKU), "unexpected image: " + image);
    }

    /**
     * The whole point of this scraper's price rule: with the offer gone it must fail rather
     * than read a number off the page. The fixture still contains unrelated money — delivery
     * thresholds and promo copy — so a DOM fallback could silently record the wrong figure.
     */
    @Test
    void throwsRatherThanReadingPriceFromDomWhenJsonLdMissing() throws IOException {
        Document doc = fixture();
        doc.select("script[type=application/ld+json]").remove();
        ScrapeException thrown = assertThrows(ScrapeException.class, () -> parse(doc));
        assertTrue(thrown.getMessage().contains("refusing to guess from the DOM"),
                thrown.getMessage());
    }

    /** A corrupt block is the same situation as a missing one: fail, never guess. */
    @Test
    void throwsWhenJsonLdIsMalformed() throws IOException {
        Document doc = fixture();
        for (var script : doc.select("script[type=application/ld+json]")) {
            script.text("{ this is not json");
        }
        assertThrows(ScrapeException.class, () -> parse(doc));
    }

    /** Proof the price is not being taken from the rendered element. */
    @Test
    void ignoresDomPriceElementEntirely() throws IOException {
        Document doc = fixture();
        doc.select("span.value[content]").forEach(el -> {
            el.attr("content", "1.00");
            el.text("£1.00");
        });
        assertEquals(0, new BigDecimal("999.00").compareTo(parse(doc).price()),
                "DOM price must not influence the result");
    }

    @Test
    void productKeyReadsSkuFromUrl() {
        assertEquals(Optional.of(SKU), scraper().productKey(PRODUCT_URL));
    }

    @Test
    void productKeyReadsSkuFromSluglessUrl() {
        assertEquals(Optional.of(SKU),
                scraper().productKey("https://www.currys.co.uk/products/10305211.html"));
    }

    @Test
    void productKeyIsEmptyForNonProductUrl() {
        assertEquals(Optional.empty(),
                scraper().productKey("https://www.currys.co.uk/computing/laptops"));
    }

    @Test
    void canonicalUrlDropsSlugButStaysFetchable() {
        assertEquals("https://www.currys.co.uk/products/10305211.html",
                scraper().canonicalUrl(PRODUCT_URL));
    }

    @Test
    void supportsOnlyItsOwnMarketplace() {
        assertTrue(scraper().supports("CURRYS"));
        assertFalse(scraper().supports("AMAZON_UK"));
        assertFalse(scraper().supports("BH_PHOTO"));
    }

    @Test
    void rejectsPageAnsweredByAnotherHost() throws IOException {
        Document doc = fixture();
        ScrapeException thrown = assertThrows(ScrapeException.class, () ->
                scraper().parse(doc, url("https://www.example.com/whatever"), PRODUCT_URL, currysConfig()));
        assertTrue(thrown.getMessage().contains("redirected off the requested marketplace"),
                thrown.getMessage());
    }

    @Test
    void rejectsPageForADifferentSku() throws IOException {
        Document doc = fixture();
        String other = "https://www.currys.co.uk/products/99999999.html";
        ScrapeException thrown = assertThrows(ScrapeException.class, () ->
                scraper().parse(doc, url(other), other, currysConfig()));
        assertTrue(thrown.getMessage().contains(SKU), thrown.getMessage());
    }

    @Test
    void rejectsPageWithoutAProductTitle() throws IOException {
        Document doc = fixture();
        doc.select("h1.product-name").remove();
        doc.select("script[type=application/ld+json]").remove();
        ScrapeException thrown = assertThrows(ScrapeException.class, () -> parse(doc));
        assertTrue(thrown.getMessage().contains("not a product page"), thrown.getMessage());
    }

    // ---- search ----

    private static Document searchFixture() throws IOException {
        try (InputStream in = CurrysScraperTest.class.getResourceAsStream("/scraper/currys/search.html")) {
            assertNotNull(in, "Missing search fixture on the test classpath");
            return Jsoup.parse(in, "UTF-8", "https://www.currys.co.uk/search?q=samsung%20ssd");
        }
    }

    @Test
    void parsesSearchResultsFromFixture() throws IOException {
        List<SearchResult> results = scraper().parseSearchResults(searchFixture());
        assertFalse(results.isEmpty(), "expected hits from the saved results page");
        SearchResult first = results.get(0);
        assertEquals("SAMSUNG T7 Portable External SSD - 2 TB, Grey", first.title());
        assertEquals("https://www.currys.co.uk/products/10217289.html", first.url());
    }

    /**
     * Currys renders each tile three times for its responsive breakpoints and links the
     * same product again from its rating and price, so the raw anchor list repeats. One
     * entry per product keeps matching from paying for the same title several times.
     */
    @Test
    void deduplicatesTheRepeatedTileMarkup() throws IOException {
        List<SearchResult> results = scraper().parseSearchResults(searchFixture());
        long distinct = results.stream().map(SearchResult::url).distinct().count();
        assertEquals(results.size(), distinct, "duplicate product URLs in results");
    }

    /** Results link with the marketing slug; storing the canonical form survives a rename. */
    @Test
    void searchResultsCarryCanonicalUrls() throws IOException {
        for (SearchResult r : scraper().parseSearchResults(searchFixture())) {
            assertFalse(r.title().isBlank(), "blank title in results");
            assertEquals(scraper().canonicalUrl(r.url()), r.url(),
                    "result should already be canonical: " + r.url());
        }
    }

    @Test
    void searchResultsAreCappedForQuota() throws IOException {
        assertTrue(scraper().parseSearchResults(searchFixture()).size() <= 3);
    }

    @Test
    void searchIgnoresABlankQueryWithoutFetching() {
        assertEquals(List.of(), scraper().search("   "));
    }

    @Test
    void isDiscoverableAsASearchableScraper() {
        assertInstanceOf(SearchableScraper.class, scraper());
    }

    @Test
    void rejectsBotChallengePage() {
        Document challenge = Jsoup.parse(
                "<html><body><div id=\"sec-if-cpt-container\"></div></body></html>", PRODUCT_URL);
        ScrapeException thrown = assertThrows(ScrapeException.class, () ->
                scraper().parse(challenge, url(PRODUCT_URL), PRODUCT_URL, currysConfig()));
        assertTrue(thrown.getMessage().contains("bot challenge"), thrown.getMessage());
    }
}
