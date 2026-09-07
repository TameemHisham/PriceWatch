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
 * Jarir Bookstore storefront scraper, following AmazonScraper's shape: fetch, verify the
 * page is really the product that was asked for, then extract.
 * <p>
 * Price comes from the schema.org JSON-LD offer first, falling back to the rendered price
 * box. Jarir publishes a complete offer — SAR, price and availability — so the fallback
 * exists only for the case where the block disappears.
 * <p>
 * Jarir fronts its pages with PerimeterX. Note that its sensor script is present on
 * perfectly healthy pages, so the sensor itself is <em>not</em> a block signal; only an
 * actual challenge page is.
 */
@Component
public class JarirScraper implements ProductScraper {

    private static final Logger log = LoggerFactory.getLogger(JarirScraper.class);
    private final MarketplaceRegistry marketplaces;
    private final Map<String, CookieStore> cookieStores = new ConcurrentHashMap<>();

    public JarirScraper(MarketplaceRegistry marketplaces) {
        this.marketplaces = marketplaces;
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

    /** Fetches a Jarir product page and extracts title, price, currency and image. */
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
            throw new ScrapeException("Jarir blocked request with a bot challenge");
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
