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
 * Currys storefront scraper, following AmazonScraper's shape: fetch, verify the page is
 * really the product that was asked for, then extract.
 * <p>
 * Price comes from the schema.org JSON-LD offer and <strong>nowhere else</strong>. Unlike
 * the other scrapers there is deliberately no DOM price fallback: if the offer is missing
 * or unparseable this throws, rather than reading a number off the page. The page carries
 * unrelated money elsewhere — delivery thresholds and promotional copy such as "Free
 * standard delivery on orders over £40" — and a wrong price recorded as though it were
 * real is worse than a scrape that fails loudly.
 */
@Component
public class CurrysScraper implements SearchableScraper {

    private static final Logger log = LoggerFactory.getLogger(CurrysScraper.class);
    private final MarketplaceRegistry marketplaces;
    private final Map<String, CookieStore> cookieStores = new ConcurrentHashMap<>();

    public CurrysScraper(MarketplaceRegistry marketplaces) {
        this.marketplaces = marketplaces;
    }

    private static final String MARKETPLACE_ID = "CURRYS";

    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    );

    /**
     * Currys' stable per-product id is the SKU trailing the slug:
     * {@code /products/asus-vivobook-…-matte-grey-10305211.html}.
     */
    private static final Pattern SKU_TOKEN = Pattern.compile("/products/(?:[^/?#]*-)?(\\d{5,})\\.html");

    private static final String[] TITLE_SELECTORS = {
            "h1.product-name"
    };

    /** A live offer renders an add-to-basket control. */
    private static final String[] PURCHASABLE_SELECTORS = {
            "button.add-to-cart",
            ".add-to-cart"
    };

    private static final String[] IMAGE_SELECTORS = {
            "meta[property=og:image]"
    };

    @Override
    public Store store() {
        return Store.CURRYS;
    }

    @Override
    public boolean supports(String marketplaceId) {
        return MARKETPLACE_ID.equals(marketplaceId);
    }

    @Override
    public Optional<String> productKey(String url) {
        Matcher matcher = SKU_TOKEN.matcher(url);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /**
     * {@code https://host/products/{sku}.html} — Currys resolves this without the slug and
     * serves the product, so the stored URL stays fetchable for later refreshes and does
     * not rot when marketing renames the slug.
     */
    @Override
    public String canonicalUrl(String url) {
        Optional<String> sku = productKey(url);
        if (sku.isEmpty()) {
            throw new ScrapeException("No Currys SKU in URL: " + url);
        }
        String host = marketplaces.configFor(MARKETPLACE_ID).getHost();
        if (host == null || host.isBlank()) {
            throw new ScrapeException("Marketplace CURRYS has no configured host");
        }
        String qualified = host.startsWith("www.") ? host : "www." + host;
        return "https://" + qualified + "/products/" + sku.get() + ".html";
    }

    /**
     * Title anchor on a results tile. Currys renders each tile three times for its
     * responsive breakpoints, and links the same product again from its rating and price,
     * so hits are de-duplicated by SKU rather than trusted to be one per product.
     */
    private static final String SEARCH_RESULT_SELECTOR = "a.pdpLink";

    /**
     * Caps how many hits are returned. Matching spends an LLM call per candidate title, so
     * one track costs 1 + MAX_RESULTS calls — a Gemini free-tier constraint, not a
     * relevance one. See TASKS.md.
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
            throw new ScrapeException("Currys blocked search with a bot challenge");
        }
        return parseSearchResults(fetched.document());
    }

    /** Split out so tests can run the real parsing against a saved results page. */
    List<SearchResult> parseSearchResults(Document document) {
        Map<String, SearchResult> bySku = new LinkedHashMap<>();
        for (Element link : document.select(SEARCH_RESULT_SELECTOR)) {
            String title = link.text().trim();
            String href = link.absUrl("href");
            if (href.isBlank()) {
                href = link.attr("href");
            }
            Optional<String> sku = productKey(href);
            if (title.isBlank() || sku.isEmpty() || bySku.containsKey(sku.get())) {
                continue;
            }
            // Store the canonical form: results link with the marketing slug, and a rename
            // upstream would otherwise leave a stale URL behind.
            bySku.put(sku.get(), new SearchResult(title, canonicalUrl(href)));
            if (bySku.size() >= MAX_RESULTS) {
                break;
            }
        }
        return new ArrayList<>(bySku.values());
    }

    /** Picks a random desktop user agent — a fixed one is an obvious bot signature. */
    private String randomUserAgent() {
        return USER_AGENTS.get(ThreadLocalRandom.current().nextInt(USER_AGENTS.size()));
    }

    /** Fetches a Currys product page and extracts title, price, currency and image. */
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
            throw new ScrapeException("Currys blocked request with a bot challenge");
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

        requireExpectedSku(document, jsonLd.orElse(null), requestedUrl);

        String imageUrl = findImage(document, jsonLd.orElse(null));

        // The offer is the only accepted price source for this store.
        if (jsonLd.isEmpty()) {
            throw new ScrapeException("No schema.org Product on page, and Currys prices are "
                    + "only read from ld+json — refusing to guess from the DOM: " + requestedUrl);
        }

        JsonLdProduct product = jsonLd.get();
        if (!product.isPurchasable()) {
            log.debug("ld+json availability {} for {} — treating as unavailable",
                    product.availability(), requestedUrl);
            return new ProductData(title, null, null, imageUrl, Availability.UNAVAILABLE);
        }

        if (!product.hasPrice()) {
            if (hasBuyButton(document)) {
                // Buyable but the offer carries no price: the page shape changed. A real failure.
                throw new ScrapeException("Offer present but ld+json carried no price, and Currys "
                        + "prices are not read from the DOM: " + requestedUrl);
            }
            log.debug("No offer price and no buy control — treating as unavailable: {}", requestedUrl);
            return new ProductData(title, null, null, imageUrl, Availability.UNAVAILABLE);
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
                    .timeout(10000);
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
     * Currys did not challenge this codebase during recon, so these markers are the generic
     * vendor interstitials rather than an observed Currys block page.
     */
    private boolean isBotChallenge(Document doc) {
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

    private void requireExpectedSku(Document doc, JsonLdProduct jsonLd, String requestedUrl) {
        Optional<String> requested = productKey(requestedUrl);
        if (requested.isEmpty()) return;

        String onPage = pageSku(doc, jsonLd);
        if (onPage == null) {
            log.debug("No SKU on page for {} — identity check skipped", requestedUrl);
            return;
        }
        if (!onPage.equals(requested.get())) {
            throw new ScrapeException("Page for SKU " + onPage + " was returned for requested SKU "
                    + requested.get() + " (" + requestedUrl + ")");
        }
    }

    /** The SKU the page claims to be for, or null when it does not say. */
    private String pageSku(Document doc, JsonLdProduct jsonLd) {
        if (jsonLd != null && jsonLd.sku() != null && !jsonLd.sku().isBlank()) {
            return jsonLd.sku().trim();
        }
        Element canonical = doc.selectFirst("link[rel=canonical]");
        if (canonical != null) {
            Optional<String> fromCanonical = productKey(canonical.attr("href"));
            if (fromCanonical.isPresent()) return fromCanonical.get();
        }
        return null;
    }

    private boolean hasBuyButton(Document doc) {
        for (String selector : PURCHASABLE_SELECTORS) {
            if (doc.selectFirst(selector) != null) return true;
        }
        return false;
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
