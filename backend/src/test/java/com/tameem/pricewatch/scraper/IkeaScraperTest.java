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

    // ---- search ----

    /**
     * IKEA's results page renders client-side and carries no product links, so search goes
     * to the JSON endpoint the storefront's own search box calls. This is a saved response
     * from it, for the same ÄNGSLILJA range the product fixture is from.
     */
    /** The query the saved response was captured for. */
    private static final String SEARCH_QUERY = "aengslilja duvet cover";

    private static String searchFixture() throws IOException {
        try (InputStream in = IkeaScraperTest.class.getResourceAsStream("/scraper/ikea/search.json")) {
            assertNotNull(in, "Missing search fixture on the test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesSearchResultsFromFixture() throws IOException {
        List<SearchResult> results = scraper().parseSearchResults(searchFixture(), SEARCH_QUERY);
        assertFalse(results.isEmpty(), "expected hits from the saved search response");
        assertEquals("ÄNGSLILJA Duvet cover and pillowcase(s)", results.get(0).title());
        assertEquals("https://www.ikea.com/us/en/p/aengslilja-duvet-cover-and-pillowcase-s-dark-blue-60636630/",
                results.get(0).url());
    }

    /**
     * The range name alone ("ÄNGSLILJA") names no product and the type alone names every
     * one, so a hit is only useful to the matcher carrying both.
     */
    @Test
    void searchResultTitlesCarryBothRangeNameAndProductType() throws IOException {
        for (SearchResult r : scraper().parseSearchResults(searchFixture(), SEARCH_QUERY)) {
            assertTrue(r.title().contains("ÄNGSLILJA"), "no range name in: " + r.title());
            assertTrue(r.title().length() > "ÄNGSLILJA".length() + 1, "no product type in: " + r.title());
        }
    }

    /** Every hit must be a product URL this scraper can go on to scrape. */
    @Test
    void everySearchResultCarriesATitleAndAProductUrl() throws IOException {
        for (SearchResult r : scraper().parseSearchResults(searchFixture(), SEARCH_QUERY)) {
            assertFalse(r.title().isBlank(), "blank title in results");
            assertTrue(scraper().productKey(r.url()).isPresent(), "not a product URL: " + r.url());
        }
    }

    @Test
    void searchResultsAreNotDuplicatedPerProduct() throws IOException {
        List<SearchResult> results = scraper().parseSearchResults(searchFixture(), SEARCH_QUERY);
        assertEquals(results.size(), results.stream().map(SearchResult::url).distinct().count(),
                "duplicate product URLs in results");
    }

    /** One track costs 1 + this many LLM calls on this store alone. */
    @Test
    void searchResultsAreCappedForQuota() throws IOException {
        assertTrue(scraper().parseSearchResults(searchFixture(), SEARCH_QUERY).size() <= 3);
    }

    @Test
    void searchIgnoresABlankQueryWithoutFetching() {
        assertEquals(List.of(), scraper().search("   "));
    }

    @Test
    void reportsAnUnparseableSearchResponseRatherThanNoHits() {
        ScrapeException thrown = assertThrows(ScrapeException.class,
                () -> scraper().parseSearchResults("not json at all", SEARCH_QUERY));
        assertTrue(thrown.getMessage().contains("Unparseable"), thrown.getMessage());
    }

    /**
     * The point of the whole change: cross-store discovery iterates only the searchable
     * scrapers, so without this IKEA can be tracked from a pasted URL but never found.
     */
    @Test
    void isDiscoverableAsASearchableScraper() {
        assertInstanceOf(SearchableScraper.class, scraper());
    }

    /**
     * The endpoint answers a phrase over 150 characters with a 400, and retailer titles run
     * past that routinely — so a long title has to be cut rather than cost the store.
     */
    @Test
    void longTitlesAreCutToAQueryTheEndpointAccepts() {
        String longTitle = "Sony WH-1000XM5 Noise Cancelling Wireless Headphones, Hi-Res Audio, Best Phone "
                + "Call Quality, 30 Hours Battery Life, Wearing Detection, Alexa Voice Assistant, "
                + "Black, UAE Version - 1-Year warranty";
        String phrase = IkeaScraper.searchPhrase(longTitle);
        assertTrue(phrase.length() <= 150, "still too long: " + phrase.length());
        assertFalse(phrase.endsWith(" "), "cut mid-gap: " + phrase);
        assertTrue(longTitle.startsWith(phrase), "not a prefix of the title: " + phrase);
        assertTrue(phrase.startsWith("Sony WH-1000XM5"), "lost the leading words: " + phrase);
    }

    @Test
    void shortTitlesArePassedThroughUnchanged() {
        assertEquals("MITTZON Conference table, round birch veneer/white, 120x75 cm",
                IkeaScraper.searchPhrase("  MITTZON Conference table, round birch veneer/white, 120x75 cm  "));
    }

    // ---- relevance gate ----

    /**
     * IKEA's search has no "no match" state — it answers a query for an SSD with pie plates —
     * and every hit handed back costs a Groq call to reject. These are real result titles it
     * returned for these real queries.
     */
    @Test
    void dropsHitsThatShareNothingWithTheQuery() throws IOException {
        String ssd = "Samsung 990 PRO 1TB PCIe 4.0 NVMe M.2 Internal SSD MZ-V9P1T0B/AM";
        assertEquals(List.of(), scraper().parseSearchResults(searchFixture(), ssd),
                "an SSD query must not come back with bed linen");
    }

    /** One generic word in common is how "Foldable" headphones reach a foldable IKEA chair. */
    @Test
    void oneSharedWordIsNotEnough() throws IOException {
        String headphones = "soundcore by Anker Q20i Hybrid ANC Foldable Headphones, 40H Cover";
        assertEquals(List.of(), scraper().parseSearchResults(searchFixture(), headphones),
                "one word in common must not be enough");
    }

    /** The range name is IKEA identity, so sharing it carries a hit on its own. */
    @Test
    void keepsAHitWhoseRangeNameIsInTheQuery() throws IOException {
        List<SearchResult> results =
                scraper().parseSearchResults(searchFixture(), "IKEA ANGSLILJA bedding set");
        assertFalse(results.isEmpty(), "the range name alone should carry the hit");
    }

    /** Accents must not split a word: ÄNGSLILJA and the angslilja another retailer writes. */
    @Test
    void matchesTheRangeNameAcrossAccents() throws IOException {
        assertFalse(scraper().parseSearchResults(searchFixture(), "ÄNGSLILJA duvet").isEmpty());
        assertFalse(scraper().parseSearchResults(searchFixture(), "angslilja duvet").isEmpty());
    }

    /** Two descriptive words carry a hit that names the thing without naming the range. */
    @Test
    void keepsAHitSharingTwoDescriptiveWords() throws IOException {
        List<SearchResult> results =
                scraper().parseSearchResults(searchFixture(), "Dark blue duvet cover 150x200");
        assertFalse(results.isEmpty(), "\"duvet\" plus \"cover\" should carry the hit");
    }

    /** With nothing to judge against, the attribute gate stays the decider. */
    @Test
    void keepsEverythingWhenTheQuerySaysNothing() throws IOException {
        assertFalse(scraper().parseSearchResults(searchFixture(), "a of 12").isEmpty());
    }

    /** The cap is the backstop, not the thing normally deciding the count. */
    @Test
    void neverReturnsMoreThanTheCapEvenWhenEveryCandidateIsPlausible() throws IOException {
        assertTrue(scraper().parseSearchResults(searchFixture(), SEARCH_QUERY).size() <= 3);
    }
}
