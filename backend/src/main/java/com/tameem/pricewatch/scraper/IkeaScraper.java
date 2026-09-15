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
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IKEA storefront scraper, following AmazonScraper's shape: fetch, verify the page is
 * really the product that was asked for, then extract.
 * <p>
 * Price comes from the schema.org JSON-LD offer first, falling back to the rendered price
 * element.
 * <p>
 * IKEA keys its locale off the <em>path</em> ({@code /us/en/}), not the host, while
 * MarketplaceRegistry resolves a marketplace by host. One configured IKEA marketplace
 * therefore covers one locale, and canonicalUrl preserves whatever locale the tracked URL
 * carried rather than forcing one.
 * <p>
 * Search does not go through ikea.com. The storefront's own results page renders
 * client-side and contains no product links at all in the served HTML, which is why this
 * scraper was not searchable for a long time and IKEA never appeared in cross-store
 * discovery. It is searched instead through the same first-party JSON endpoint the
 * storefront's own search box calls, which returns the product URL outright.
 */
@Component
public class IkeaScraper implements SearchableScraper {

    private static final Logger log = LoggerFactory.getLogger(IkeaScraper.class);
    private final MarketplaceRegistry marketplaces;
    private final Map<String, CookieStore> cookieStores = new ConcurrentHashMap<>();

    public IkeaScraper(MarketplaceRegistry marketplaces) {
        this.marketplaces = marketplaces;
    }

    private static final String MARKETPLACE_ID = "IKEA";

    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    );

    /**
     * IKEA's article number trails the slug: {@code /us/en/p/aengslilja-…-40585226/}.
     * <p>
     * Series and combination products carry an "s" in front of it
     * ({@code /ae/en/p/mittzon-…-s99513930/}), which is decorative in the URL — both
     * {@code /p/-99513930/} and {@code /p/-s99513930/} resolve to the same product. Only
     * the digits are captured, because that is the form the page itself states in
     * data-product-no and (dotted) in its ld+json sku, and the identity check compares
     * against those.
     */
    private static final Pattern ARTICLE_TOKEN = Pattern.compile("/p/(?:[^/?#]*-)?s?(\\d{8})(?:[/?#]|$)");

    /** Locale lives in the path, e.g. "/us/en" in "/us/en/p/…". */
    private static final Pattern LOCALE_TOKEN = Pattern.compile("^(/[a-z]{2}/[a-z]{2})/");

    /** Optional currency symbol then a number: "$39.99". */
    private static final Pattern PRICE_TOKEN =
            Pattern.compile("(?:[A-Z]{2,3}|[^\\w\\s])\\s?\\d[\\d.,]*");

    private static final String[] TITLE_SELECTORS = {
            "h1"
    };

    /**
     * Fallback only — the offer is the preferred source. The screen-reader span carries the
     * whole price as one string ("Price $ 39.99"); the visible price is split across
     * currency/integer/decimal spans, which is far more awkward to reassemble.
     */
    private static final String[] PRICE_ELEMENT_SELECTORS = {
            ".pipcom-price-module__current-price .pipcom-price__sr-text",
            ".pipcom-price__sr-text"
    };

    /**
     * IKEA renders no add-to-cart button server-side — the CTA is client-rendered — so
     * sellability is read from the product container's own attribute instead of a button.
     */
    private static final String[] PURCHASABLE_SELECTORS = {
            "[data-online-sellable=true]"
    };

    private static final String[] IMAGE_SELECTORS = {
            "meta[property=og:image]"
    };

    @Override
    public Store store() {
        return Store.IKEA;
    }

    @Override
    public boolean supports(String marketplaceId) {
        return MARKETPLACE_ID.equals(marketplaceId);
    }

    @Override
    public Optional<String> productKey(String url) {
        Matcher matcher = ARTICLE_TOKEN.matcher(url);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /**
     * {@code https://host/{locale}/p/-{article}/} — IKEA resolves a product from the article
     * number alone and redirects to the full slug, so the stored URL survives a slug rename.
     * The locale segment is carried over from the tracked URL because it, not the host,
     * determines currency and language.
     */
    @Override
    public String canonicalUrl(String url) {
        Optional<String> article = productKey(url);
        if (article.isEmpty()) {
            throw new ScrapeException("No IKEA article number in URL: " + url);
        }
        String host = marketplaces.configFor(MARKETPLACE_ID).getHost();
        if (host == null || host.isBlank()) {
            throw new ScrapeException("Marketplace IKEA has no configured host");
        }
        String qualified = host.startsWith("www.") ? host : "www." + host;
        try {
            String path = new URI(url).getPath();
            Matcher locale = LOCALE_TOKEN.matcher(path == null ? "" : path);
            if (!locale.find()) {
                throw new ScrapeException("No locale segment (e.g. /us/en) in IKEA URL: " + url);
            }
            return "https://" + qualified + locale.group(1) + "/p/-" + article.get() + "/";
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
     * Host of the JSON search endpoint ikea.com's own search box calls.
     * <p>
     * Deliberately not the storefront host: {@code /{locale}/search/?q=} returns a shell
     * with zero product links, so Jsoup can read nothing out of it. This endpoint answers
     * the same query with the product URL already resolved.
     */
    private static final String SEARCH_API_HOST = "sik.search.blue.cdtapps.com";

    /**
     * Ceiling on hits returned, not the thing that normally decides how many there are —
     * {@link #isPlausible} is. Matching spends an LLM call per candidate title, so every hit
     * handed back costs one whether it is the product or not; this is the backstop that
     * keeps a broad query from spending the whole budget. See TASKS.md.
     */
    private static final int MAX_RESULTS = 3;

    /**
     * Candidates asked of the endpoint, before the relevance gate thins them.
     * <p>
     * More than {@link #MAX_RESULTS} because the gate drops hits for free and the LLM only
     * ever sees what survives it, so a wider look costs one HTTP request and nothing else —
     * while giving a genuine match ranked fourth a chance to be seen at all.
     */
    private static final int SEARCH_CANDIDATES = MAX_RESULTS * 2;

    /**
     * The endpoint refuses a longer phrase outright — {@code HTTP 400, "Search phrase is
     * more than 150 characters"} — and retailer titles routinely run past it, so the query
     * is cut rather than the store being skipped. Safe to cut: this search matches loosely,
     * so the leading words carry the query.
     */
    private static final int MAX_QUERY_CHARS = 150;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public List<SearchResult> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        ScrapeProperties.MarketplaceConfig marketplace = marketplaces.configFor(MARKETPLACE_ID);
        String url = "https://" + SEARCH_API_HOST + "/" + searchLocale(marketplace)
                + "/search-result-page?q="
                + URLEncoder.encode(searchPhrase(query), StandardCharsets.UTF_8)
                + "&size=" + SEARCH_CANDIDATES;
        return parseSearchResults(fetchSearch(url, marketplace), query);
    }

    /** The title as a query the endpoint will accept: collapsed, and cut on a word boundary. */
    static String searchPhrase(String title) {
        String cleaned = title.trim().replaceAll("\\s+", " ");
        if (cleaned.length() <= MAX_QUERY_CHARS) {
            return cleaned;
        }
        String cut = cleaned.substring(0, MAX_QUERY_CHARS);
        int lastSpace = cut.lastIndexOf(' ');
        return (lastSpace > 0 ? cut.substring(0, lastSpace) : cut).trim();
    }

    /**
     * The {@code country/language} pair the endpoint is keyed by, taken from the same
     * configuration the product fetch uses so search and scrape cannot drift to different
     * storefronts. Country comes from delivery-country, language from the first tag of
     * accept-language ("en-US,en;q=0.9" -> "en").
     */
    private String searchLocale(ScrapeProperties.MarketplaceConfig marketplace) {
        String country = marketplace.getDeliveryCountry();
        if (country == null || country.isBlank()) {
            throw new ScrapeException("Marketplace IKEA has no configured delivery country");
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
        return country.toLowerCase(Locale.ROOT) + "/" + language;
    }

    /**
     * Split out so tests can run the real parsing against a saved response.
     *
     * @param queryTitle the title searched for, which the relevance gate judges hits against
     */
    List<SearchResult> parseSearchResults(String json, String queryTitle) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (RuntimeException e) {
            throw new ScrapeException("Unparseable IKEA search response", e);
        }

        Set<String> queryTokens = significantTokens(queryTitle);
        List<SearchResult> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : root.path("searchResultPage").path("products").path("main").path("items")) {
            JsonNode product = item.path("product");
            String url = text(product.path("pipUrl"));
            // A row can be a shelf header or a retired article rather than a product.
            if (url.isBlank() || productKey(url).isEmpty() || !seen.add(url)) {
                continue;
            }
            // IKEA splits a product title in two: the range name ("ÄNGSLILJA") and what the
            // thing actually is ("Duvet cover and pillowcase(s)"). Matching needs both —
            // the range name alone names no product, and the type alone names every one.
            String name = text(product.path("name"));
            String title = (name + " " + text(product.path("typeName"))).trim();
            if (title.isBlank()) {
                continue;
            }
            if (!isPlausible(queryTokens, name, title)) {
                log.debug("Dropping IKEA hit '{}' — shares nothing with the query", title);
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
     * Whether a hit is worth spending an LLM call on.
     * <p>
     * IKEA's search has no concept of "no match": ask it for "Samsung 990 PRO 1TB SSD" and it
     * answers, in all seriousness, with three pie plates. Every one of those used to reach
     * the attribute gate, which costs a Groq call each to reject — on a budget the project
     * has already run dry once. Nothing here decides whether two products are the same; that
     * is still the gate's job. It only drops hits that cannot possibly be, so the calls are
     * spent on candidates rather than on pie plates.
     * <p>
     * A hit qualifies on either of two counts, and the first is what carries it: IKEA
     * identity <em>is</em> the range name, so a hit whose range name appears in the query is
     * a real candidate however differently the rest is worded. Failing that, two shared
     * descriptive words ("6-drawer dresser") also carry it, which covers a title that names
     * the thing without naming the range. One shared word does not — that is how "Foldable"
     * headphones reach a foldable IKEA chair.
     */
    private static boolean isPlausible(Set<String> queryTokens, String name, String title) {
        if (queryTokens.isEmpty()) {
            return true; // nothing to judge against; let the attribute gate decide
        }
        String rangeName = name.isBlank() ? "" : name.trim().split("\\s+")[0];
        for (String token : significantTokens(rangeName)) {
            if (queryTokens.contains(token)) {
                return true;
            }
        }
        int shared = 0;
        for (String token : significantTokens(title)) {
            if (queryTokens.contains(token) && ++shared >= 2) {
                return true;
            }
        }
        return false;
    }

    /**
     * Words worth comparing: lower-cased, stripped of accents so "ÄNGSLILJA" and the
     * "angslilja" another retailer writes are the same word, and short ones dropped because
     * "of" and "cm" are shared by everything.
     */
    private static Set<String> significantTokens(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String folded = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : folded.split("[^a-z0-9]+")) {
            if (token.length() >= 3) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        String value = node.isObject() || node.isArray() ? "" : node.asString();
        return value == null ? "" : value.trim();
    }

    /**
     * Fetches the search endpoint as JSON rather than HTML.
     * <p>
     * Keeps the marketplace's proxy, because the endpoint prices and stocks by the
     * requesting IP exactly as the storefront does.
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
                    .maxBodySize(0)
                    .timeout(15000);
            if (marketplace.getProxyHost() != null && !marketplace.getProxyHost().isBlank()) {
                connection.proxy(marketplace.getProxyHost(), marketplace.getProxyPort());
            }
            return connection.execute().body();
        } catch (IOException e) {
            throw new ScrapeException("Failed to fetch IKEA search results: " + url, e);
        }
    }

    /** Fetches an IKEA product page and extracts title, price, currency and image. */
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
            throw new ScrapeException("IKEA blocked request with a bot challenge");
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

        requireExpectedArticle(document, jsonLd.orElse(null), requestedUrl);

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

        // Fallback: the rendered price element.
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
     * IKEA did not challenge this codebase during recon, so these markers are the generic
     * vendor interstitials rather than an observed IKEA block page.
     */
    private boolean isBotChallenge(Document doc) {
        if (doc.selectFirst("#sec-if-cpt-container") != null) return true;
        if (doc.selectFirst("#px-captcha") != null) return true;
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

    private void requireExpectedArticle(Document doc, JsonLdProduct jsonLd, String requestedUrl) {
        Optional<String> requested = productKey(requestedUrl);
        if (requested.isEmpty()) return;

        String onPage = pageArticle(doc, jsonLd);
        if (onPage == null) {
            log.debug("No article number on page for {} — identity check skipped", requestedUrl);
            return;
        }
        if (!onPage.equals(requested.get())) {
            throw new ScrapeException("Page for article " + onPage + " was returned for requested article "
                    + requested.get() + " (" + requestedUrl + ")");
        }
    }

    /**
     * The article number the page claims to be for, or null when it does not say. IKEA
     * publishes it dotted in ld+json ("405.852.26") and undotted in URLs ("40585226").
     */
    private String pageArticle(Document doc, JsonLdProduct jsonLd) {
        Element container = doc.selectFirst("[data-product-no]");
        if (container != null) {
            String value = container.attr("data-product-no").trim();
            if (value.matches("\\d{8}")) return value;
        }
        if (jsonLd != null && jsonLd.sku() != null && !jsonLd.sku().isBlank()) {
            String digits = jsonLd.sku().replace(".", "").trim();
            if (digits.matches("\\d{8}")) return digits;
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
        // Last resort: the container attribute. Carries the number but no currency, so the
        // caller pairs it with the configured marketplace rather than parsing a symbol.
        Element container = doc.selectFirst("[data-product-price]");
        if (container != null) {
            String value = container.attr("data-product-price");
            if (!value.isBlank()) return value.trim();
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
        String cleaned = raw.replaceAll("[^0-9.,]", "");

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

    /** Maps the currency symbol in the price text to an ISO code, or UNKNOWN when unrecognised. */
    private String parseCurrency(String raw) {
        if (raw == null) return null;

        if (raw.contains("£")) return "GBP";
        if (raw.contains("€")) return "EUR";
        if (raw.contains("SAR") || raw.contains("SR")) return "SAR";
        if (raw.contains("AED")) return "AED";
        if (raw.contains("$")) return "USD";

        return "UNKNOWN"; // unknown symbol
    }
}
