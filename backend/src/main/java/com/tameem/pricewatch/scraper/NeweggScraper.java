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
 * Newegg storefront scraper, following AmazonScraper's shape: fetch, verify the page is
 * really the product that was asked for, then extract.
 * <p>
 * Dispatch is by marketplace id via ScraperRegistry, so this is a normal bean.
 */
@Component
public class NeweggScraper implements ProductScraper {

    private static final Logger log = LoggerFactory.getLogger(NeweggScraper.class);
    private final MarketplaceRegistry marketplaces;
    private final Map<String, CookieStore> cookieStores = new ConcurrentHashMap<>();

    public NeweggScraper(MarketplaceRegistry marketplaces) {
        this.marketplaces = marketplaces;
    }

    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    );

    /**
     * Newegg's stable per-product id is the item number, e.g. "N82E16819113877". Marketplace
     * listings use a different prefix ("9SIC0X3KG23952"), so match on shape, not on "N82E".
     */
    private static final Pattern ITEM_PATH_TOKEN = Pattern.compile("/p/([A-Z0-9]{10,})");

    /** Seller-specific links carry the item number as a query parameter instead. */
    private static final Pattern ITEM_QUERY_TOKEN = Pattern.compile("[?&]Item=([A-Z0-9]{10,})");

    /**
     * The live buy box stamps a campaign suffix onto the price class ("price-current_2026"
     * sits next to a "is-product-blackfriday-first" box), so the unsuffixed class is kept as
     * a fallback rather than assumed gone.
     */
    private static final String[] PRICE_ELEMENT_SELECTORS = {
            ".product-buy-box .product-price .price-current_2026",
            ".product-buy-box .product-price .price-current",
            ".product-buy-box .price-current_2026",
            ".product-buy-box .price-current",
            ".product-price .price-current_2026",
            ".product-price .price-current"
    };

    private static final String[] TITLE_SELECTORS = {
            "h1.product-title"
    };

    /** A live offer renders this; an out-of-stock page renders a notify button instead. */
    private static final String[] PURCHASABLE_SELECTORS = {
            "#ProductBuy .btn-primary",
            ".product-buy button.btn-primary"
    };

    private static final String[] PAGE_ITEM_SELECTORS = {
            ".breadcrumbs li.is-active em"
    };

    private static final String[] IMAGE_SELECTORS = {
            "meta[property=og:image]",
            ".product-view-img-original"
    };

    private static final String MARKETPLACE_ID = "NEWEGG";

    @Override
    public Store store() {
        return Store.NEWEGG;
    }

    @Override
    public boolean supports(String marketplaceId) {
        return MARKETPLACE_ID.equals(marketplaceId);
    }

    /**
     * {@code https://host/p/ITEM} — Newegg resolves this without the slug and redirects to
     * the full path, so the stored URL stays fetchable for later refreshes.
     */
    @Override
    public String canonicalUrl(String url) {
        Optional<String> item = productKey(url);
        if (item.isEmpty()) {
            throw new ScrapeException("No Newegg item number in URL: " + url);
        }
        String host = marketplaces.configFor(MARKETPLACE_ID).getHost();
        if (host == null || host.isBlank()) {
            throw new ScrapeException("Marketplace NEWEGG has no configured host");
        }
        String qualified = host.startsWith("www.") ? host : "www." + host;
        return "https://" + qualified + "/p/" + item.get();
    }

    @Override
    public Optional<String> productKey(String url) {
        Matcher path = ITEM_PATH_TOKEN.matcher(url);
        if (path.find()) {
            return Optional.of(path.group(1));
        }
        Matcher query = ITEM_QUERY_TOKEN.matcher(url);
        if (query.find()) {
            return Optional.of(query.group(1));
        }
        return Optional.empty();
    }

    /** Picks a random desktop user agent — a fixed one is an obvious bot signature. */
    private String randomUserAgent() {
        return USER_AGENTS.get(ThreadLocalRandom.current().nextInt(USER_AGENTS.size()));
    }

    /** Fetches a Newegg product page and extracts title, price, currency and image. */
    public ProductData scrape(String url) {
        String marketplaceId = marketplaces.idFor(url);
        ScrapeProperties.MarketplaceConfig marketplace = marketplaces.configFor(marketplaceId);
        Fetched fetched = fetch(url, marketplaceId, marketplace);
        return parse(fetched.document(), fetched.finalUrl(), url, marketplace);
    }

    /**
     * Everything after the fetch, split out so tests can run the real extraction against a
     * saved fixture instead of a live request.
     */
    ProductData parse(Document document, URL finalUrl, String requestedUrl,
                      ScrapeProperties.MarketplaceConfig marketplace) {

        if (isBotChallenge(document)) {
            throw new ScrapeException("Newegg blocked request with a bot challenge");
        }

        requireExpectedHost(finalUrl, marketplace, requestedUrl);

        String title = findFirstMatch(document, TITLE_SELECTORS);
        if (title != null) {
            title = title.trim();
        }
        if (title == null || title.isBlank()) {
            throw new ScrapeException("No product title on page — not a product page: " + requestedUrl);
        }

        requireExpectedItem(document, requestedUrl);

        String imageUrl = findImage(document);

        if (isExplicitlyUnavailable(document)) {
            return new ProductData(title, null, null, imageUrl, Availability.UNAVAILABLE);
        }

        String rawPrice = findPrice(document);
        if (rawPrice == null) {
            if (hasBuyButton(document)) {
                // Buyable but unreadable: the page shape changed. A real failure.
                throw new ScrapeException("Offer present but no price element matched for URL: " + requestedUrl);
            }
            if (hasAnyPriceElement(document)) {
                throw new ScrapeException("No buy option and no readable product price for URL: " + requestedUrl);
            }
            log.debug("No offer markers and no price elements at all — treating as unavailable: {}", requestedUrl);
            return new ProductData(title, null, null, imageUrl, Availability.UNAVAILABLE);
        }

        BigDecimal price = parsePrice(rawPrice);
        String currency = parseCurrency(rawPrice);

        return new ProductData(title, price, currency, imageUrl, Availability.AVAILABLE);
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
     * Newegg fronts its edge with a bot challenge that answers 200 with an interstitial
     * rather than an error status. Not yet observed live from this codebase — the markers
     * are the generic Akamai container and Newegg's own challenge path.
     */
    private boolean isBotChallenge(Document doc) {
        if (doc.selectFirst("#sec-if-cpt-container") != null) return true;
        return doc.html().contains("areyouahuman");
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

    private void requireExpectedItem(Document doc, String requestedUrl) {
        Optional<String> requested = productKey(requestedUrl);
        if (requested.isEmpty()) return;

        String onPage = pageItemNumber(doc);
        if (onPage == null) {
            log.debug("No item number element on page for {} — identity check skipped", requestedUrl);
            return;
        }
        if (!onPage.equalsIgnoreCase(requested.get())) {
            throw new ScrapeException("Page for item " + onPage + " was returned for requested item "
                    + requested.get() + " (" + requestedUrl + ")");
        }
    }

    /** The item number the page claims to be for, or null when it does not say. */
    private String pageItemNumber(Document doc) {
        for (String selector : PAGE_ITEM_SELECTORS) {
            Element element = doc.selectFirst(selector);
            if (element == null) continue;
            String value = element.text();
            if (!value.isBlank()) return value.trim();
        }
        // The canonical URL carries it too, and survives breadcrumb markup changes.
        Element canonical = doc.selectFirst("meta[property=og:url]");
        if (canonical != null) {
            Optional<String> fromCanonical = productKey(canonical.attr("content"));
            if (fromCanonical.isPresent()) return fromCanonical.get();
        }
        return null;
    }

    private String findPrice(Document doc) {
        for (String selector : PRICE_ELEMENT_SELECTORS) {
            for (Element el : doc.select(selector)) {
                String value = priceTextFrom(el);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * The buy-box price is a bare currency symbol text node followed by
     * {@code <strong>469</strong><sup>.00</sup>}, so the element's own text already reads
     * "$469.00". Empty price shells also match the selectors, hence the digit check.
     */
    private String priceTextFrom(Element priceElement) {
        String text = priceElement.text();
        if (hasDigit(text)) {
            return text;
        }

        Element whole = priceElement.selectFirst("strong");
        if (whole == null) {
            return null;
        }
        Element fraction = priceElement.selectFirst("sup");
        String assembled = priceElement.ownText()
                + whole.text()
                + (fraction == null ? "" : fraction.text());
        return hasDigit(assembled) ? assembled : null;
    }

    private boolean hasDigit(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) return true;
        }
        return false;
    }

    /**
     * The retailer says outright that this cannot be bought here. Newegg spells this out in
     * the buy box rather than with a dedicated element id, so this reads the box's text.
     * Not yet verified against a live out-of-stock page.
     */
    private boolean isExplicitlyUnavailable(Document doc) {
        Element buyBox = doc.selectFirst(".product-buy-box");
        if (buyBox == null) return false;
        String text = buyBox.text().toUpperCase(Locale.ROOT);
        return text.contains("OUT OF STOCK") || text.contains("SOLD OUT");
    }

    /**
     * A live offer renders an add-to-cart button. Presence alone is not enough: an
     * out-of-stock page renders an equally primary "Auto Notify" button in the same slot.
     */
    private boolean hasBuyButton(Document doc) {
        for (String selector : PURCHASABLE_SELECTORS) {
            for (Element el : doc.select(selector)) {
                if (el.text().toUpperCase(Locale.ROOT).contains("ADD TO CART")) return true;
            }
        }
        return false;
    }

    private boolean hasAnyPriceElement(Document doc) {
        return doc.selectFirst(".price-current, .price-current_2026") != null;
    }

    private String findImage(Document doc) {
        for (String selector : IMAGE_SELECTORS) {
            for (Element el : doc.select(selector)) {
                String value = el.tagName().equals("meta") ? el.attr("content") : el.attr("src");
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
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
        if (raw.contains("C$")) return "CAD";
        if (raw.contains("$")) return "USD";

        return "UNKNOWN"; // unknown symbol
    }
}
