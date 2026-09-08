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
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
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
 */
@Component
public class IkeaScraper implements ProductScraper {

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

    /** IKEA's article number trails the slug: {@code /us/en/p/aengslilja-…-40585226/}. */
    private static final Pattern ARTICLE_TOKEN = Pattern.compile("/p/(?:[^/?#]*-)?(\\d{8})(?:[/?#]|$)");

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
