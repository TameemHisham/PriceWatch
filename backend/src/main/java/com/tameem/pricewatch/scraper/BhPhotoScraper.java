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
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookieStore;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B&amp;H Photo Video storefront scraper, following AmazonScraper's shape: fetch, verify the
 * page is really the product that was asked for, then extract.
 * <p>
 * Price comes from schema.org JSON-LD first and the DOM selector chain only as a fallback.
 * B&amp;H is the reason that order matters here: its price element carries the build-hashed
 * class {@code price__9gLfjPSjp}, which can change on any deploy, while the JSON-LD offer is
 * load-bearing for Google rich results and so has an external reason to stay put. The stable
 * {@code data-selenium} hook is kept as the fallback.
 * <p>
 * Since September 2026 the site sits behind Cloudflare bot management, which answers a share
 * of requests with a challenge instead of the page. {@link #fetch} is where that is dealt
 * with; read its note before changing how requests are made here.
 */
@Component
public class BhPhotoScraper implements SearchableScraper {

    private static final Logger log = LoggerFactory.getLogger(BhPhotoScraper.class);
    private final MarketplaceRegistry marketplaces;
    private final ScraperRateLimiter rateLimiter;
    private final Map<String, CookieStore> cookieStores = new ConcurrentHashMap<>();

    /** Convenience for tests that only exercise parsing/fetch; uses a default breaker. */
    public BhPhotoScraper(MarketplaceRegistry marketplaces) {
        this(marketplaces, new ScraperRateLimiter());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BhPhotoScraper(MarketplaceRegistry marketplaces, ScraperRateLimiter rateLimiter) {
        this.marketplaces = marketplaces;
        this.rateLimiter = rateLimiter;
    }

    private static final String MARKETPLACE_ID = "BH_PHOTO";

    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    );

    /**
     * B&amp;H's stable per-product id is the item number in the path:
     * {@code /c/product/1997421-REG/slug.html}. The trailing letters are a condition code,
     * captured separately so a canonical URL can preserve it.
     */
    private static final Pattern ITEM_TOKEN = Pattern.compile("/c/product/(\\d{4,})-([A-Z]{2,4})");

    /** Same path without a condition code, which older links sometimes use. */
    private static final Pattern ITEM_TOKEN_BARE = Pattern.compile("/c/product/(\\d{4,})");

    /** Optional currency symbol then a number: "$3,199.00". */
    private static final Pattern PRICE_TOKEN =
            Pattern.compile("(?:[A-Z]{2,3}|[^\\w\\s])\\s?\\d[\\d.,]*");

    private static final String[] TITLE_SELECTORS = {
            "h1[data-selenium=productTitle]",
            "[data-selenium=productTitle]"
    };

    /**
     * Fallback only. The generated class on this element is deliberately not listed — it
     * changes between builds; the data-selenium hook is what B&amp;H keeps stable.
     */
    private static final String[] PRICE_ELEMENT_SELECTORS = {
            "[data-selenium=pricingPrice]",
            "[data-selenium=pricingPriceWrapper] [data-selenium=pricingPrice]"
    };

    /** A live offer renders one of these; both "Add to Cart" and "Preorder" are real offers. */
    private static final String[] PURCHASABLE_SELECTORS = {
            "[data-selenium=addToCartLink]",
            "[data-selenium=addToCartButton]"
    };

    private static final String[] IMAGE_SELECTORS = {
            "meta[property=og:image]",
            "[data-selenium=inlineMediaMainImage]"
    };

    /** Text B&H shows in place of an offer when the item cannot be bought. */
    private static final String[] UNAVAILABLE_PHRASES = {
            "NO LONGER AVAILABLE",
            "DISCONTINUED",
            "SOLD OUT"
    };

    @Override
    public Store store() {
        return Store.BH_PHOTO;
    }

    @Override
    public boolean supports(String marketplaceId) {
        return MARKETPLACE_ID.equals(marketplaceId);
    }

    @Override
    public Optional<String> productKey(String url) {
        Matcher matcher = ITEM_TOKEN.matcher(url);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        Matcher bare = ITEM_TOKEN_BARE.matcher(url);
        if (bare.find()) {
            return Optional.of(bare.group(1));
        }
        return Optional.empty();
    }

    /**
     * {@code https://host/c/product/1997421-REG/} — B&amp;H resolves this without the slug and
     * redirects to the full path, so the stored URL stays fetchable for later refreshes.
     */
    @Override
    public String canonicalUrl(String url) {
        Optional<String> item = productKey(url);
        if (item.isEmpty()) {
            throw new ScrapeException("No B&H item number in URL: " + url);
        }
        Matcher matcher = ITEM_TOKEN.matcher(url);
        String condition = matcher.find() ? matcher.group(2) : "REG";
        return "https://" + hostFor(url) + "/c/product/" + item.get() + "-" + condition + "/";
    }

    private String hostFor(String url) {
        String configured = marketplaces.configFor(MARKETPLACE_ID).getHost();
        if (configured == null || configured.isBlank()) {
            throw new ScrapeException("Marketplace BH_PHOTO has no configured host");
        }
        return configured.startsWith("www.") ? configured : "www." + configured;
    }

    /** Anchor carrying both the result title and its product link, one per hit. */
    private static final String SEARCH_RESULT_SELECTOR = "[data-selenium=miniProductPageProductNameLink]";

    /**
     * Caps how many hits are returned. Matching spends an LLM call per candidate title, so
     * one track costs 1 + MAX_RESULTS calls. Set to 3 to stay well inside the Gemini free
     * tier's per-minute and per-day limits without putting throttling delays in the request
     * path — a quota constraint, not a relevance one. Worth raising on a paid key; see
     * TASKS.md.
     */
    private static final int MAX_RESULTS = 3;

    @Override
    public List<SearchResult> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        ScrapeProperties.MarketplaceConfig marketplace = marketplaces.configFor(MARKETPLACE_ID);
        String url = "https://" + hostFor("https://" + marketplace.getHost())
                + "/c/search?q=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
        return rateLimiter.guard(MARKETPLACE_ID, () -> {
            Fetched fetched = fetch(url, MARKETPLACE_ID, marketplace);
            if (isBotChallenge(fetched.document())) {
                throw new EdgeChallengeException("B&H blocked search with a bot challenge");
            }
            return parseSearchResults(fetched.document());
        });
    }

    /** Split out so tests can run the real parsing against a saved results page. */
    List<SearchResult> parseSearchResults(Document document) {
        List<SearchResult> results = new ArrayList<>();
        for (Element link : document.select(SEARCH_RESULT_SELECTOR)) {
            String title = link.text().trim();
            String href = link.absUrl("href");
            if (href.isBlank()) {
                href = link.attr("href");
            }
            if (title.isBlank() || href.isBlank() || productKey(href).isEmpty()) {
                continue;
            }
            results.add(new SearchResult(title, href));
            if (results.size() >= MAX_RESULTS) {
                break;
            }
        }
        return results;
    }

    /** Fetches a B&H product page and extracts title, price, currency and image. */
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
            throw new EdgeChallengeException("B&H blocked request with a bot challenge");
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

        requireExpectedItem(document, jsonLd.orElse(null), requestedUrl);

        String imageUrl = findImage(document, jsonLd.orElse(null));

        if (isExplicitlyUnavailable(document)) {
            return new ProductData(title, null, null, imageUrl, Availability.UNAVAILABLE);
        }

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

    /**
     * Fetches a B&amp;H URL, retrying across the user agent rotation while the edge answers
     * with a bot challenge.
     * <p>
     * B&amp;H put Cloudflare bot management in front of the site in September 2026 — it
     * answers {@code HTTP 403} with {@code cf-mitigated: challenge} and a "Just a moment…"
     * interstitial. Three things follow from that, and all three are handled here.
     * <p>
     * First, the block was invisible. Jsoup throws {@link org.jsoup.HttpStatusException} on a
     * 403 before {@code parse()} ever runs, so {@link #isBotChallenge} never saw the page and
     * every block surfaced as the generic "Failed to fetch page" — indistinguishable from a
     * timeout or a DNS failure. Errors are read off the response here instead, which is the
     * rule the recon notes already set down: assert on content, never on status code.
     * <p>
     * Second, the challenge is not absolute, and which agent asks matters. Measured against
     * the live site over 36 interleaved requests: the Safari agent was served 9 times in 12,
     * each Chrome agent only 4 in 12. Jsoup sends no {@code Sec-CH-UA} client hints, which is
     * consistent for Safari and inconsistent for a request claiming Chrome — the same
     * fingerprint mismatch that got Namshi dropped. Retrying across the rotation therefore
     * turns a challenged request into a served one most of the time. The rotation still starts
     * at a random agent rather than always at Safari: ordering it would cut the average
     * attempts per fetch from about 1.9 to 1.3, but that advantage is one 36-request sample
     * from one address at one moment, and spending it would make Safari the standing
     * signature — the fixed-agent trade the Namshi note refused. At sweep pacing the extra
     * request costs nothing that matters.
     * <p>
     * Third, and separately: past a certain rate B&amp;H stops challenging and starts rate
     * limiting, with {@code HTTP 429} to every agent alike. Retrying that would only spend
     * three requests against a limit already tripped, so a 429 fails immediately instead.
     */
    private Fetched fetch(String url, String marketplaceId, ScrapeProperties.MarketplaceConfig marketplace) {
        IOException lastFailure = null;
        boolean challenged = false;
        List<String> agents = rotatedUserAgents();
        for (int attempt = 0; attempt < agents.size(); attempt++) {
            if (attempt > 0) {
                pauseBetweenAttempts();
            }
            String userAgent = agents.get(attempt);
            try {
                Connection.Response response = execute(url, userAgent, marketplaceId, marketplace);
                // A Cloudflare managed challenge is not retryable — no user agent answers a JS
                // check Jsoup cannot run — so it stops the fetch here rather than rotating on.
                EdgeChallenge.failFastIfMitigated(response);
                if (response.statusCode() == 429) {
                    // Rate limited, not fingerprinted. Another agent would be another request
                    // into a limit that is already tripped, so this one stops here. Measured
                    // on the live site: once 429s start, every agent gets them.
                    throw new ScrapeException("B&H rate-limited this client (HTTP 429) for "
                            + url + " — backing off rather than retrying");
                }
                if (isEdgeChallenge(response)) {
                    challenged = true;
                    log.debug("B&H answered {} with a bot challenge (HTTP {}) — retrying on the next user agent",
                            url, response.statusCode());
                    continue;
                }
                if (response.statusCode() >= 400) {
                    // Not a challenge, so retrying it would only repeat it.
                    throw new ScrapeException("B&H answered HTTP " + response.statusCode()
                            + " for " + url);
                }
                return new Fetched(response.parse(), response.url());
            } catch (IOException e) {
                // A transport failure is worth one more agent too, but keep it: a run that
                // never got past the transport should report that, not a block.
                lastFailure = e;
            }
        }
        if (challenged) {
            // Named as a block even when a transport failure also occurred: the block is the
            // fact that explains the listing, and the one worth acting on.
            throw new EdgeChallengeException("B&H blocked all " + agents.size() + " attempts at " + url
                    + " with a bot challenge — the edge check cannot be answered by Jsoup",
                    lastFailure);
        }
        throw new ScrapeException("Failed to fetch page: " + url, lastFailure);
    }

    /** Keeps three attempts from leaving as one burst, which is its own bot signal. */
    private void pauseBetweenAttempts() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The rotation, starting at a random agent, so a fixed one is never the signature. */
    private List<String> rotatedUserAgents() {
        int start = ThreadLocalRandom.current().nextInt(USER_AGENTS.size());
        List<String> rotated = new ArrayList<>(USER_AGENTS.size());
        for (int i = 0; i < USER_AGENTS.size(); i++) {
            rotated.add(USER_AGENTS.get((start + i) % USER_AGENTS.size()));
        }
        return rotated;
    }

    /** Package-private, not private, so a test can substitute the transport and prove the
     *  retry behaviour of {@link #fetch} without a live request. */
    Connection.Response execute(String url, String userAgent, String marketplaceId,
                                ScrapeProperties.MarketplaceConfig marketplace) throws IOException {
        CookieStore cookies = cookieStores.computeIfAbsent(
                marketplaceId, id -> new CookieManager().getCookieStore());

        Connection connection = Jsoup.connect(url)
                .userAgent(userAgent) // simulates a real user
                .header("Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", marketplace.getAcceptLanguage())
                .header("Accept-Encoding", "gzip, deflate") // NOT br: Jsoup cannot decode Brotli,
                // and a br response parses to garbage with no error — Newegg serves it
                .header("Cache-Control", "no-cache")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .cookieStore(cookies) // get cookies
                .ignoreHttpErrors(true) // read the block off the response instead of throwing on it
                .maxBodySize(0) // product pages run past Jsoup's default 2MB cap
                .timeout(10000);
        marketplace.applyProxy(connection, log);
        return connection.execute();
    }

    /**
     * Whether this response is an edge bot check rather than the page that was asked for.
     * <p>
     * Cloudflare states it outright in {@code cf-mitigated}, which is the reliable signal and
     * costs nothing to read. The body markers are the fallback for a challenge whose header
     * is absent, and are only worth materialising the body for on a status that could
     * plausibly be one — a challenge served with a 200, the shape the recon notes flag as
     * fooling a status-code check, is caught later by {@link #isBotChallenge} on the parsed
     * document instead of being paid for on every healthy fetch.
     */
    private boolean isEdgeChallenge(Connection.Response response) {
        String mitigated = response.header("cf-mitigated");
        if (mitigated != null && !mitigated.isBlank()) {
            return true;
        }
        int status = response.statusCode();
        if (status != 403 && status != 503) {
            return false; // 429 is rate limiting, handled before this and never retried
        }
        try {
            return hasChallengeMarkers(response.body());
        } catch (RuntimeException e) {
            return false; // nothing readable to judge on
        }
    }

    /** Vendor interstitial markers, in the body of a response. */
    static boolean hasChallengeMarkers(String body) {
        if (body == null) return false;
        return body.contains("challenges.cloudflare.com")
                || body.contains("cf-browser-verification")
                || body.contains("__cf_chl")
                || body.contains("sec-if-cpt-container")
                || body.contains("captcha-delivery")
                || body.contains("validateCaptcha");
    }
    /**
     * Last-ditch check on a page that was fetched successfully.
     * <p>
     * A challenge normally never reaches here — {@link #fetch} recognises it on the
     * response and retries or fails loudly — so this covers only an interstitial served
     * with a 200 and no {@code cf-mitigated} header, which is the shape the recon notes
     * record for Noon, Walmart and Lazada.
     */
    private boolean isBotChallenge(Document doc) {
        if (doc.selectFirst("#sec-if-cpt-container") != null) return true;
        if (doc.selectFirst("#challenge-form, #cf-challenge-running") != null) return true;
        String html = doc.html();
        return html.contains("captcha-delivery")
                || html.contains("validateCaptcha")
                || html.contains("challenges.cloudflare.com")
                || html.contains("cf-browser-verification");
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

    private void requireExpectedItem(Document doc, JsonLdProduct jsonLd, String requestedUrl) {
        Optional<String> requested = productKey(requestedUrl);
        if (requested.isEmpty()) return;

        String onPage = pageItemNumber(doc, jsonLd);
        if (onPage == null) {
            log.debug("No item number on page for {} — identity check skipped", requestedUrl);
            return;
        }
        if (!onPage.equals(requested.get())) {
            throw new ScrapeException("Page for item " + onPage + " was returned for requested item "
                    + requested.get() + " (" + requestedUrl + ")");
        }
    }

    /** The item number the page claims to be for, or null when it does not say. */
    private String pageItemNumber(Document doc, JsonLdProduct jsonLd) {
        Element canonical = doc.selectFirst("link[rel=canonical]");
        if (canonical != null) {
            Optional<String> fromCanonical = productKey(canonical.attr("href"));
            if (fromCanonical.isPresent()) return fromCanonical.get();
        }
        if (jsonLd != null && jsonLd.url() != null) {
            Optional<String> fromJsonLd = productKey(jsonLd.url());
            if (fromJsonLd.isPresent()) return fromJsonLd.get();
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

    /** The retailer says outright that this cannot be bought here. */
    private boolean isExplicitlyUnavailable(Document doc) {
        Element status = doc.selectFirst("[data-selenium=stockStatus]");
        if (status == null) return false;
        String text = status.text().toUpperCase(Locale.ROOT);
        for (String phrase : UNAVAILABLE_PHRASES) {
            if (text.contains(phrase)) return true;
        }
        return false;
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
                // comma is decimal separator, e.g. "1.234,56"
                cleaned = cleaned.replace(".", "").replace(",", ".");
            } else {
                // comma is thousands separator, e.g. "1,234.56"
                cleaned = cleaned.replace(",", "");
            }
        } else if (cleaned.contains(",")) {
            // Only comma present - assume thousands separator unless it looks decimal (2 digits after)
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
        if (raw.contains("$")) return "USD";

        return "UNKNOWN"; // unknown symbol
    }
}
