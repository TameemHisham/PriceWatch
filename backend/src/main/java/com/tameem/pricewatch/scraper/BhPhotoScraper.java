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
 */
@Component
public class BhPhotoScraper implements ProductScraper {

    private static final Logger log = LoggerFactory.getLogger(BhPhotoScraper.class);
    private final MarketplaceRegistry marketplaces;
    private final Map<String, CookieStore> cookieStores = new ConcurrentHashMap<>();

    public BhPhotoScraper(MarketplaceRegistry marketplaces) {
        this.marketplaces = marketplaces;
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

    /** Picks a random desktop user agent — a fixed one is an obvious bot signature. */
    private String randomUserAgent() {
        return USER_AGENTS.get(ThreadLocalRandom.current().nextInt(USER_AGENTS.size()));
    }

    /** Fetches a B&H product page and extracts title, price, currency and image. */
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
            throw new ScrapeException("B&H blocked request with a bot challenge");
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
                    // and a br response parses to garbage with no error — Newegg serves it
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
     * B&H did not challenge this codebase during recon, so these markers are the generic
     * vendor interstitials rather than an observed B&H block page.
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
