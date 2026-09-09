package com.tameem.pricewatch.scraper;

import com.tameem.pricewatch.config.MarketplaceRegistry;
import com.tameem.pricewatch.entity.Store;
import com.tameem.pricewatch.config.ScrapeProperties;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookieStore;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flipkart storefront scraper, following AmazonScraper's shape: fetch, verify the page is
 * really the product that was asked for, then extract.
 * <p>
 * Price comes from the schema.org JSON-LD offer and <strong>nowhere else</strong>, for two
 * reasons. Flipkart's CSS classes are build-generated hashes ({@code v1zwn21n},
 * {@code _1psv1zeb9}) that change on any deploy, so no DOM selector is stable enough to
 * fall back to. And the page renders several rupee figures — list price, discounted price,
 * an EMI instalment, exchange offers — so a text scan could quietly record a monthly EMI
 * amount as the product price. A missing offer throws instead.
 */
@Component
public class FlipkartScraper implements SearchableScraper {

    private static final Logger log = LoggerFactory.getLogger(FlipkartScraper.class);
    private final MarketplaceRegistry marketplaces;
    private final Map<String, CookieStore> cookieStores = new ConcurrentHashMap<>();

    public FlipkartScraper(MarketplaceRegistry marketplaces) {
        this.marketplaces = marketplaces;
    }

    private static final String MARKETPLACE_ID = "FLIPKART";

    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    );

    /**
     * Flipkart's listing id, which is what its own canonical URL uses:
     * {@code /ai-nova-2-neo-5g-blue-128-gb/p/itmdcaa61dad2064}. The {@code pid} query
     * parameter carries a separate product id (also the ld+json sku) — see productId().
     */
    private static final Pattern ITEM_TOKEN = Pattern.compile("/p/(itm[a-z0-9]+)");

    /** The catalogue product id, e.g. "MOBHZ8ZMTFJZHQQ6". */
    private static final Pattern PID_TOKEN = Pattern.compile("[?&]pid=([A-Z0-9]{10,})");

    private static final String[] TITLE_SELECTORS = {
            "h1"
    };

    private static final String[] IMAGE_SELECTORS = {
            "meta[property=og:image]"
    };

    @Override
    public Store store() {
        return Store.FLIPKART;
    }

    @Override
    public boolean supports(String marketplaceId) {
        return MARKETPLACE_ID.equals(marketplaceId);
    }

    @Override
    public Optional<String> productKey(String url) {
        Matcher matcher = ITEM_TOKEN.matcher(url);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** The catalogue product id from a "pid" query parameter, when the URL carries one. */
    Optional<String> productId(String url) {
        Matcher matcher = PID_TOKEN.matcher(url);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /**
     * Path only, dropping query and fragment — the form Flipkart's own canonical link uses.
     * <p>
     * The slug is kept, but harmlessly: any slug resolves for a given item id (verified with
     * a placeholder), so a slug rename upstream cannot orphan a stored listing the way it
     * could for Jarir.
     */
    @Override
    public String canonicalUrl(String url) {
        if (productKey(url).isEmpty()) {
            throw new ScrapeException("No Flipkart item id in URL: " + url);
        }
        String host = marketplaces.configFor(MARKETPLACE_ID).getHost();
        if (host == null || host.isBlank()) {
            throw new ScrapeException("Marketplace FLIPKART has no configured host");
        }
        try {
            String path = new URI(url).getPath();
            if (path == null || path.isBlank()) {
                throw new ScrapeException("No path in Flipkart URL: " + url);
            }
            String qualified = host.startsWith("www.") ? host : "www." + host;
            return "https://" + qualified + path;
        } catch (URISyntaxException e) {
            throw new ScrapeException("Unparseable URL: " + url, e);
        }
    }

    /**
     * Results tile anchors that carry a title attribute.
     * <p>
     * The attribute is used rather than the anchor's text on purpose: Flipkart truncates
     * the rendered name with an ellipsis ("Samsung T7 Shield 1TB USB 3.2 Gen 2(10
     * Gbps),IP65 Rated..."), while the attribute holds it in full. Matching runs entirely
     * on titles, so feeding it a truncated one attacks the step that decides correctness.
     * Class names are no use here — they are build-hashed, as on the product pages.
     */
    private static final String SEARCH_RESULT_SELECTOR = "a[href*=/p/itm][title]";

    /**
     * Caps how many hits are returned. Matching spends an LLM call per candidate title, so
     * one track costs 1 + MAX_RESULTS calls per searchable store — a Gemini free-tier
     * constraint, not a relevance one. See TASKS.md.
     */
    private static final int MAX_RESULTS = 3;

    @Override
    public List<SearchResult> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        ScrapeProperties.MarketplaceConfig marketplace = marketplaces.configFor(MARKETPLACE_ID);
        String host = marketplace.getHost();
        String qualified = host.startsWith("www.") ? host : "www." + host;
        String url = "https://" + qualified + "/search?q="
                + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
        Fetched fetched = fetch(url, MARKETPLACE_ID, marketplace);
        if (isBotChallenge(fetched.document())) {
            throw new ScrapeException("Flipkart blocked search with a bot challenge");
        }
        return parseSearchResults(fetched.document());
    }

    /** Split out so tests can run the real parsing against a saved results page. */
    List<SearchResult> parseSearchResults(Document document) {
        Map<String, SearchResult> byItem = new LinkedHashMap<>();
        for (Element link : document.select(SEARCH_RESULT_SELECTOR)) {
            String title = link.attr("title").trim();
            String href = link.absUrl("href");
            if (href.isBlank()) {
                href = link.attr("href");
            }
            Optional<String> item = productKey(href);
            if (title.isBlank() || item.isEmpty() || byItem.containsKey(item.get())) {
                continue;
            }
            // Canonical form drops the tracking query string results are linked with.
            byItem.put(item.get(), new SearchResult(title, canonicalUrl(href)));
            if (byItem.size() >= MAX_RESULTS) {
                break;
            }
        }
        return new ArrayList<>(byItem.values());
    }

    /** Picks a random desktop user agent — a fixed one is an obvious bot signature. */
    private String randomUserAgent() {
        return USER_AGENTS.get(ThreadLocalRandom.current().nextInt(USER_AGENTS.size()));
    }

    /** Fetches a Flipkart product page and extracts title, price, currency and image. */
    public ProductData scrape(String url) {
        String marketplaceId = marketplaces.idFor(url);
        ScrapeProperties.MarketplaceConfig marketplace = marketplaces.configFor(marketplaceId);
        Fetched fetched = fetch(url, marketplaceId, marketplace);
        return parse(fetched.document(), fetched.finalUrl(), url, marketplace);
    }

    /**
     * Everything after the fetch, separated so tests can run the real extraction against a
     * saved fixture instead of a live request.
     */
    ProductData parse(Document document, URL finalUrl, String requestedUrl,
                      ScrapeProperties.MarketplaceConfig marketplace) {

        if (isBotChallenge(document)) {
            throw new ScrapeException("Flipkart blocked request with a bot challenge");
        }

        requireExpectedHost(finalUrl, marketplace, requestedUrl);

        Optional<JsonLdProduct> jsonLd = JsonLdProduct.from(document);

        String title = findFirstMatch(document, TITLE_SELECTORS);
        if (title == null && jsonLd.isPresent()) {
            title = jsonLd.get().name();
        }
        if (title != null) {
            title = title.trim();
        }
        if (title == null || title.isBlank()) {
            throw new ScrapeException("No product title on page — not a product page: " + requestedUrl);
        }

        requireExpectedItem(document, requestedUrl);

        String imageUrl = findImage(document, jsonLd.orElse(null));

        // The offer is the only accepted price source for this store.
        if (jsonLd.isEmpty()) {
            throw new ScrapeException("No schema.org Product on page, and Flipkart prices are "
                    + "only read from ld+json — its DOM classes are build-hashed and the page "
                    + "carries EMI and exchange figures that a text scan would confuse for the "
                    + "price: " + requestedUrl);
        }

        JsonLdProduct product = jsonLd.get();
        if (!product.isPurchasable()) {
            log.debug("ld+json availability {} for {} — treating as unavailable",
                    product.availability(), requestedUrl);
            return new ProductData(title, null, null, imageUrl, Availability.UNAVAILABLE);
        }
        if (!product.hasPrice()) {
            throw new ScrapeException("ld+json offer carried no price, and Flipkart prices are "
                    + "not read from the DOM: " + requestedUrl);
        }

        String currency = product.currency() == null || product.currency().isBlank()
                ? "UNKNOWN"
                : product.currency();
        return new ProductData(title, product.price(), currency, imageUrl, Availability.AVAILABLE);
    }

    private record Fetched(Document document, URL finalUrl) {}

    private Fetched fetch(String url, String marketplaceId, ScrapeProperties.MarketplaceConfig marketplace) {
        try {
            CookieStore cookies = cookieStores.computeIfAbsent(
                    marketplaceId, id -> new CookieManager().getCookieStore());

            Connection connection = Jsoup.connect(url)
                    .userAgent(randomUserAgent()) // simulates a real user
                    .header("Accept",
                            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", marketplace.getAcceptLanguage())
                    .header("Accept-Encoding", "gzip, deflate") // NOT br: Jsoup cannot decode Brotli,
                    // and a br response parses to garbage with no error
                    .header("Cache-Control", "no-cache")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")
                    .cookieStore(cookies) // get cookies
                    .maxBodySize(0) // product pages run past Jsoup's default 2MB cap
                    .timeout(15000);
            if (marketplace.getProxyHost() != null && !marketplace.getProxyHost().isBlank()) {
                connection.proxy(marketplace.getProxyHost(), marketplace.getProxyPort());
            } else {
                log.debug("No proxy for delivery country {} — scraping from local egress",
                        marketplace.getDeliveryCountry());
            }

            Connection.Response response = connection.execute();
            return new Fetched(response.parse(), response.url());
        } catch (IOException e) {
            throw new ScrapeException("Failed to fetch page: " + url, e);
        }
    }

    /**
     * Deliberately narrow. A healthy Flipkart page ships a client-side error dictionary that
     * contains the literal string "Access Denied" for its own 5xx screens, so matching that
     * text would reject every scrape. Only a served challenge page counts.
     */
    private boolean isBotChallenge(Document doc) {
        if (doc.selectFirst("#px-captcha") != null) return true;
        if (doc.selectFirst("#sec-if-cpt-container") != null) return true;
        String html = doc.html();
        return html.contains("captcha-delivery") || html.contains("validateCaptcha");
    }

    private void requireExpectedHost(URL finalUrl, ScrapeProperties.MarketplaceConfig marketplace,
                                     String requestedUrl) {
        String configured = marketplace.getHost();
        if (configured == null || configured.isBlank()) return;

        String expected = configured.toLowerCase();
        String actual = finalUrl.getHost() == null ? "" : finalUrl.getHost().toLowerCase();
        if (actual.equals(expected) || actual.endsWith("." + expected)) return;

        throw new ScrapeException("Request for " + requestedUrl + " was answered by " + actual
                + ", not " + expected + " — redirected off the requested marketplace");
    }

    /**
     * Identity is checked against the canonical link rather than the ld+json sku: the sku is
     * the catalogue product id ("MOBHZ8ZMTFJZHQQ6") while URLs carry the listing id
     * ("itmdcaa61dad2064"), so the two are not comparable.
     */
    private void requireExpectedItem(Document doc, String requestedUrl) {
        Optional<String> requested = productKey(requestedUrl);
        if (requested.isEmpty()) return;

        Element canonical = doc.selectFirst("link[rel=canonical]");
        if (canonical == null) {
            log.debug("No canonical link on page for {} — identity check skipped", requestedUrl);
            return;
        }
        Optional<String> onPage = productKey(canonical.attr("href"));
        if (onPage.isEmpty()) {
            log.debug("No item id in canonical link for {} — identity check skipped", requestedUrl);
            return;
        }
        if (!onPage.get().equals(requested.get())) {
            throw new ScrapeException("Page for item " + onPage.get() + " was returned for requested item "
                    + requested.get() + " (" + requestedUrl + ")");
        }
    }

    private String findImage(Document doc, JsonLdProduct jsonLd) {
        for (String selector : IMAGE_SELECTORS) {
            for (Element el : doc.select(selector)) {
                String value = el.tagName().equals("meta") ? el.attr("content") : el.attr("src");
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return jsonLd == null ? null : jsonLd.image();
    }

    private String findFirstMatch(Document doc, String[] selectors) {
        for (String selector : selectors) {
            for (Element el : doc.select(selector)) {
                String value = el.text();
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }
}
