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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookieStore;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jarir Bookstore storefront scraper, following AmazonScraper's shape: fetch, verify the
 * page is really the product that was asked for, then extract.
 * <p>
 * Price comes from the schema.org JSON-LD offer first, falling back to the rendered price
 * box. Jarir publishes a complete offer — SAR, pricemarketplace.applyProxy(connection, log); and availability — so the fallback
 * exists only for the case where the block disappears.
 * <p>
 * Jarir fronts its pages with PerimeterX. Note that its sensor script is present on
 * perfectly healthy pages, so the sensor itself is <em>not</em> a block signal; only an
 * actual challenge page is.
 * <p>
 * Search does not go through a results page. {@code /catalogsearch/result/?q=} is a Nuxt
 * shell that carries no product links at all, which is why this scraper was not searchable
 * for a long time and Jarir never appeared in cross-store discovery. Its search box is
 * served by a hosted search provider, and that provider's endpoint — the one the storefront
 * itself calls — answers with the product slug already resolved.
 */
@Component
public class JarirScraper implements SearchableScraper {

    private static final Logger log = LoggerFactory.getLogger(JarirScraper.class);
    private final MarketplaceRegistry marketplaces;
    private final ScraperRateLimiter rateLimiter;
    private final Map<String, CookieStore> cookieStores = new ConcurrentHashMap<>();

    /** Convenience for tests that only exercise parsing; uses a default breaker. */
    public JarirScraper(MarketplaceRegistry marketplaces) {
        this(marketplaces, new ScraperRateLimiter());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public JarirScraper(MarketplaceRegistry marketplaces, ScraperRateLimiter rateLimiter) {
        this.marketplaces = marketplaces;
        this.rateLimiter = rateLimiter;
    }

    private static final String MARKETPLACE_ID = "JARIR";

    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    );

    /** Jarir's stable per-product id trails the slug: {@code /sa-en/prang-…-27178.html}. */
    private static final Pattern SKU_TOKEN = Pattern.compile("-(\\d{4,})\\.html");

    /** Optional currency code or symbol, then a number: "SR 39". */
    private static final Pattern PRICE_TOKEN =
            Pattern.compile("(?:[A-Z]{2,3}|[^\\w\\s])\\s?\\d[\\d.,]*");

    private static final String[] TITLE_SELECTORS = {
            "h1.product-title__title",
            ".product-title__title",
            "h1"
    };

    /** Fallback only — the offer is the preferred source. */
    private static final String[] PRICE_ELEMENT_SELECTORS = {
            ".price-box--pdp .price--pdp",
            ".price-box--pdp .price",
            ".price--pdp"
    };

    /** A live offer renders an add-to-cart control. */
    private static final String[] PURCHASABLE_SELECTORS = {
            "[data-testid=addToCart]",
            "button.button--add-to-cart"
    };

    private static final String[] IMAGE_SELECTORS = {
            "meta[property=og:image]"
    };

    @Override
    public Store store() {
        return Store.JARIR;
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
     * Keeps the full path, dropping only query and fragment.
     * <p>
     * Unlike Newegg, B&amp;H and Currys, Jarir does <strong>not</strong> resolve a product
     * from its id alone — both {@code /sa-en/27178.html} and {@code /sa-en/x-27178.html}
     * return 404 — so the slug is load-bearing and cannot be trimmed. The consequence is
     * that a stored listing would stop refreshing if Jarir ever renamed a slug, which is a
     * risk the other three do not carry.
     */
    @Override
    public String canonicalUrl(String url) {
        if (productKey(url).isEmpty()) {
            throw new ScrapeException("No Jarir SKU in URL: " + url);
        }
        String host = marketplaces.configFor(MARKETPLACE_ID).getHost();
        if (host == null || host.isBlank()) {
            throw new ScrapeException("Marketplace JARIR has no configured host");
        }
        try {
            URI uri = new URI(url);
            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                throw new ScrapeException("No path in Jarir URL: " + url);
            }
            String qualified = host.startsWith("www.") ? host : "www." + host;
            return "https://" + qualified + path;
        } catch (URISyntaxException e) {
            throw new ScrapeException("Unparseable URL: " + url, e);
        }
    }

    /** Picks a random desktop user agent — a fixed one is an obvious bot signature. */
    private String randomUserAgent() {
        return USER_AGENTS.get(ThreadLocalRandom.current().nextInt(USER_AGENTS.size()));
    }

    // ---- search ----

    /**
     * Host of the hosted search provider jarir.com's own search box queries. Not the
     * storefront host: {@code /catalogsearch/result/?q=} serves a shell with no product
     * links, so there is nothing on it for Jsoup to select.
     */
    private static final String SEARCH_API_HOST = "ac.cnstrc.com";

    /**
     * Client identifiers the endpoint requires but does not authenticate — they exist so the
     * provider can attribute a session, and it rejects a request that omits them. Fixed
     * rather than generated because this is not a browser session and inventing a per-call
     * identity would only pollute the storefront's own analytics.
     */
    private static final String SEARCH_CLIENT_ID = "pricewatch";
    private static final String SEARCH_CLIENT_VERSION = "ciojs-client-2.60.0";

    /**
     * Caps how many hits are returned, for the same reason B&amp;H does: matching spends an
     * LLM call per candidate title, so one track costs 1 + MAX_RESULTS calls per searchable
     * store. A quota constraint, not a relevance one — see TASKS.md.
     */
    private static final int MAX_RESULTS = 3;

    /**
     * How many leading words of the title to query with, widest first.
     * <p>
     * The provider matches tokens rather than meaning: a whole retailer title
     * ("… Black, UAE Version - 1-Year warranty") matches nothing at all, and even five words
     * often does. Short queries are what its index is built for — it is wired to a search
     * box that people type into. Narrowing one rung at a time keeps the most specific query
     * that actually returns something, rather than jumping straight to two words and
     * matching the whole brand. At most three requests, none of which costs an LLM call.
     */
    private static final int[] QUERY_WORD_LIMITS = {4, 3, 2};

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public List<SearchResult> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        ScrapeProperties.MarketplaceConfig marketplace = marketplaces.configFor(MARKETPLACE_ID);
        String key = marketplace.getSearchKey();
        if (key == null || key.isBlank()) {
            // Loud rather than an empty list: "not configured" is a different fact from
            // "searched and found nothing", and the caller logs and carries on either way.
            throw new ScrapeException("Marketplace JARIR has no configured search key");
        }

        return rateLimiter.guard(MARKETPLACE_ID, () -> {
            String previous = null;
            for (int limit : QUERY_WORD_LIMITS) {
                String phrase = firstWords(query, limit);
                if (phrase.isBlank() || phrase.equals(previous)) {
                    continue; // a short title narrows to the same phrase at every rung
                }
                previous = phrase;
                List<SearchResult> hits = parseSearchResults(
                        fetchSearch(searchUrl(phrase, key), marketplace), marketplace);
                if (!hits.isEmpty()) {
                    return hits;
                }
                log.debug("No Jarir hits for '{}' — narrowing the query", phrase);
            }
            return List.of();
        });
    }

    /** The first {@code limit} whitespace-separated words, without any trailing separator. */
    static String firstWords(String title, int limit) {
        String[] words = title.trim().split("\\s+");
        StringBuilder phrase = new StringBuilder();
        for (int i = 0; i < Math.min(limit, words.length); i++) {
            if (!phrase.isEmpty()) phrase.append(' ');
            phrase.append(words[i]);
        }
        return phrase.toString().replaceAll("[,;:\\-]+$", "").trim();
    }

    private String searchUrl(String phrase, String key) {
        // The query is a path segment here, not a parameter, so the form encoder's "+" for
        // a space would be sent literally.
        String encodedQuery = URLEncoder.encode(phrase, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "https://" + SEARCH_API_HOST + "/search/" + encodedQuery
                + "?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&i=" + SEARCH_CLIENT_ID
                + "&s=1"
                + "&c=" + SEARCH_CLIENT_VERSION
                + "&num_results_per_page=" + MAX_RESULTS
                + "&section=Products";
    }

    /** Split out so tests can run the real parsing against a saved response. */
    List<SearchResult> parseSearchResults(String json, ScrapeProperties.MarketplaceConfig marketplace) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (RuntimeException e) {
            throw new ScrapeException("Unparseable Jarir search response", e);
        }

        String host = marketplace.getHost();
        if (host == null || host.isBlank()) {
            throw new ScrapeException("Marketplace JARIR has no configured host");
        }
        String qualified = host.startsWith("www.") ? host : "www." + host;
        String prefix = "https://" + qualified + "/" + searchLocale(marketplace) + "/";

        List<SearchResult> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode hit : root.path("response").path("results")) {
            // The provider returns the slug alone, relative to the locale segment.
            String slug = text(hit.path("data").path("url"));
            String title = text(hit.path("value"));
            if (slug.isBlank() || title.isBlank()) {
                continue;
            }
            String url = slug.startsWith("http") ? slug : prefix + slug.replaceFirst("^/", "");
            if (productKey(url).isEmpty() || !seen.add(url)) {
                continue;
            }
            results.add(new SearchResult(title, url));
            if (results.size() >= MAX_RESULTS) {
                break;
            }
        }
        return results;
    }

    /**
     * The {@code country-language} segment Jarir keys its storefront by ("sa-en"), taken
     * from the same configuration the product fetch uses so search and scrape cannot drift
     * to different storefronts.
     */
    private String searchLocale(ScrapeProperties.MarketplaceConfig marketplace) {
        String country = marketplace.getDeliveryCountry();
        if (country == null || country.isBlank()) {
            throw new ScrapeException("Marketplace JARIR has no configured delivery country");
        }
        String acceptLanguage = marketplace.getAcceptLanguage();
        String language = "en";
        if (acceptLanguage != null && !acceptLanguage.isBlank()) {
            String first = acceptLanguage.split(",")[0].trim();
            int dash = first.indexOf('-');
            String primary = dash > 0 ? first.substring(0, dash) : first;
            if (primary.matches("[A-Za-z]{2}")) {
                language = primary.toLowerCase(Locale.ROOT);
            }
        }
        return country.toLowerCase(Locale.ROOT) + "-" + language;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        String value = node.isObject() || node.isArray() ? "" : node.asString();
        return value == null ? "" : value.trim();
    }

    /**
     * Fetches the search endpoint as JSON rather than HTML.
     * <p>
     * Keeps the marketplace's proxy, because the endpoint answers with the catalogue and
     * pricing of whichever country the request appears to come from, exactly as the
     * storefront does.
     */
    private String fetchSearch(String url, ScrapeProperties.MarketplaceConfig marketplace) {
        try {
            Connection connection = Jsoup.connect(url)
                    .userAgent(randomUserAgent())
                    .header("Accept", "application/json")
                    .header("Accept-Language", marketplace.getAcceptLanguage())
                    .header("Accept-Encoding", "gzip, deflate")
                    .header("Referer", "https://www." + marketplace.getHost() + "/")
                    .ignoreContentType(true) // the response is JSON, not a document
                    .ignoreHttpErrors(true) // read the block off the response instead of throwing on it
                    .maxBodySize(0)
                    .timeout(15000);
            marketplace.applyProxy(connection, log);
            Connection.Response response = connection.execute();
            EdgeChallenge.check(response); // cf-mitigated / 403-503 interstitial → EdgeChallengeException
            if (response.statusCode() >= 400) {
                throw new ScrapeException("Jarir search answered HTTP " + response.statusCode() + " for " + url);
            }
            return response.body();
        } catch (IOException e) {
            throw new ScrapeException("Failed to fetch Jarir search results: " + url, e);
        }
    }

    /** Fetches a Jarir product page and extracts title, price, currency and image. */
    public ProductData scrape(String url) {
        String marketplaceId = marketplaces.idFor(url);
        ScrapeProperties.MarketplaceConfig marketplace = marketplaces.configFor(marketplaceId);
        return rateLimiter.guard(marketplaceId, () -> {
            Fetched fetched = fetch(url, marketplaceId, marketplace);
            return parse(fetched.document(), fetched.finalUrl(), url, marketplace);
        });
    }

    /**
     * Everything after the fetch, separated so tests can run the real extraction against a
     * saved fixture instead of a live request.
     */
    ProductData parse(Document document, URL finalUrl, String requestedUrl,
                      ScrapeProperties.MarketplaceConfig marketplace) {

        if (isBotChallenge(document)) {
            throw new EdgeChallengeException("Jarir blocked request with a bot challenge");
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

        // Preferred source: the schema.org offer.
        if (jsonLd.isPresent() && jsonLd.get().hasPrice()) {
            JsonLdProduct product = jsonLd.get();
            if (!product.isPurchasable()) {
                log.debug("ld+json availability {} for {} — treating as unavailable",
                        product.availability(), requestedUrl);
                return new ProductData(title, null, null, imageUrl, Availability.UNAVAILABLE);
            }
            String currency = product.currency() == null || product.currency().isBlank()
                    ? "UNKNOWN"
                    : product.currency();
            return new ProductData(title, product.price(), currency, imageUrl, Availability.AVAILABLE);
        }

        // Fallback: the rendered price box.
        log.debug("No usable ld+json price for {} — falling back to DOM selectors", requestedUrl);
        String rawPrice = findPrice(document);
        if (rawPrice == null) {
            if (hasBuyButton(document)) {
                // Buyable but unreadable: the page shape changed. A real failure.
                throw new ScrapeException("Offer present but no price element matched for URL: " + requestedUrl);
            }
            log.debug("No offer markers and no price elements at all — treating as unavailable: {}", requestedUrl);
            return new ProductData(title, null, null, imageUrl, Availability.UNAVAILABLE);
        }

        return new ProductData(title, parsePrice(rawPrice), parseCurrency(rawPrice),
                imageUrl, Availability.AVAILABLE);
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
                    .ignoreHttpErrors(true) // read the block off the response instead of throwing on it
                    .maxBodySize(0) // product pages run past Jsoup's default 2MB cap
                    .timeout(15000);
            marketplace.applyProxy(connection, log);

            Connection.Response response = connection.execute();
            EdgeChallenge.check(response); // cf-mitigated / 403-503 interstitial → EdgeChallengeException
            if (response.statusCode() >= 400) {
                throw new ScrapeException("Jarir answered HTTP " + response.statusCode() + " for " + url);
            }
            return new Fetched(response.parse(), response.url());
        } catch (IOException e) {
            throw new ScrapeException("Failed to fetch page: " + url, e);
        }
    }

    /**
     * PerimeterX challenge detection.
     * <p>
     * Deliberately does not test for the PerimeterX sensor itself: a healthy Jarir product
     * page already ships a "_px" reference, so matching on that would fail every scrape.
     * Only an actual challenge page is a block.
     */
    private boolean isBotChallenge(Document doc) {
        if (doc.selectFirst("#px-captcha") != null) return true;
        String html = doc.html();
        return html.contains("px-captcha")
                || html.contains("Access to this page has been denied")
                || html.contains("captcha-delivery");
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

    private String findPrice(Document doc) {
        for (String selector : PRICE_ELEMENT_SELECTORS) {
            for (Element el : doc.select(selector)) {
                String text = el.text();
                if (text != null && !text.isBlank() && PRICE_TOKEN.matcher(text).find()) {
                    return text.trim();
                }
            }
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

    /** Parses a displayed price, resolving whether comma or dot is the decimal separator. */
    private BigDecimal parsePrice(String raw) {
        if (raw == null || raw.isBlank()) return null;
        // Strip everything except digits, dot, comma - then normalize
        String cleaned = raw.replaceAll("[^0-9.,]", "");

        // Handle "1,234.56" vs "1.234,56" formats
        if (cleaned.contains(",") && cleaned.contains(".")) {
            if (cleaned.lastIndexOf(',') > cleaned.lastIndexOf('.')) {
                cleaned = cleaned.replace(".", "").replace(",", ".");
            } else {
                cleaned = cleaned.replace(",", "");
            }
        } else if (cleaned.contains(",")) {
            if (cleaned.matches(".*,\\d{2}$")) {
                cleaned = cleaned.replace(",", ".");
            } else {
                cleaned = cleaned.replace(",", "");
            }
        }

        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw new ScrapeException("Could not parse price text: " + raw);
        }
    }

    /** Maps the displayed currency to an ISO code. Jarir renders Saudi Riyals as "SR". */
    private String parseCurrency(String raw) {
        if (raw == null) return null;

        if (raw.contains("SAR") || raw.contains("SR") || raw.contains("ر.س")) return "SAR";
        if (raw.contains("QAR") || raw.contains("QR")) return "QAR";
        if (raw.contains("£")) return "GBP";
        if (raw.contains("€")) return "EUR";
        if (raw.contains("$")) return "USD";

        return "UNKNOWN"; // unknown symbol
    }
}
