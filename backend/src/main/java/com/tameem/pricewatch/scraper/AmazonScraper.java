package com.tameem.pricewatch.scraper;

import com.tameem.pricewatch.config.MarketplaceRegistry;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Component
public class AmazonScraper implements ProductScraper {

    private static final Logger log = LoggerFactory.getLogger(AmazonScraper.class); // manages logs
    private final MarketplaceRegistry marketplaces; // manging the marketplace
     private final Map<String, CookieStore> cookieStores = new ConcurrentHashMap<>(); // deals with cookies

    public AmazonScraper(MarketplaceRegistry marketplaces) {
        this.marketplaces = marketplaces;
    }

    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    );

    /** Optional currency code or symbol, then a number: "£7.73", "AED 1,724.76". */
    private static final Pattern PRICE_TOKEN =
            Pattern.compile("(?:[A-Z]{2,3}|[^\\w\\s])\\s?\\d[\\d.,]*");

    private static final Pattern ASIN_TOKEN =
            Pattern.compile("/(?:dp|gp/product|gp/aw/d)/([A-Z0-9]{10})");

    private static final String[] PRICE_LABEL_SELECTORS = {
            "#apex-pricetopay-accessibility-label",
            "#corePriceDisplay_desktop_feature_div .aok-offscreen"
    };

    private static final String[] PRICE_ELEMENT_SELECTORS = {
            "#corePriceDisplay_desktop_feature_div .apex-pricetopay-value",
            ".priceToPay",
            ".apex-pricetopay-value",
            "#corePriceDisplay_desktop_feature_div .a-price:not(.a-text-price)",
            "#corePrice_feature_div .a-price:not(.a-text-price)",
            "#apex_desktop .a-price:not(.a-text-price)",
            "#buybox .a-price:not(.a-text-price)",
            "#centerCol .a-price:not(.a-text-price)",
            "#ppd .a-price:not(.a-text-price)"
    };

    private static final String[] PRICE_SELECTORS = {
            "#corePriceDisplay_desktop_feature_div .apex-pricetopay-value .a-offscreen",
            ".priceToPay .a-offscreen",
            ".apex-pricetopay-value .a-offscreen",
            "#corePriceDisplay_desktop_feature_div .a-price:not(.a-text-price) .a-offscreen",
            "#corePrice_feature_div .a-price:not(.a-text-price) .a-offscreen",
            "#apex_desktop .a-price:not(.a-text-price) .a-offscreen",
            "#buybox .a-price:not(.a-text-price) .a-offscreen",
            "#priceblock_ourprice",
            "#priceblock_dealprice",
            "#centerCol .a-price:not(.a-text-price) .a-offscreen",
            "#ppd .a-price:not(.a-text-price) .a-offscreen"
    };

    private static final String[] TITLE_SELECTORS = {
            "#productTitle"
    };

    private static final String[] UNAVAILABLE_SELECTORS = {
            "#outOfStock",
            "#exports_desktop_outOfStock_buybox"
    };

    /** A live offer always renders one of these. */
    private static final String[] PURCHASABLE_SELECTORS = {
            "#add-to-cart-button",
            "#buy-now-button"
    };

    private static final String[] PAGE_ASIN_SELECTORS = {
            "input#ASIN",
            "#ppd[data-csa-c-asin]"
    };

    private static final String[] IMAGE_SELECTORS = {
            "#landingImage",
            "#imgTagWrapperId img"
    };

//    public static void main(String[] args) {
//        AmazonScraper myScraper = new AmazonScraper();
//        ProductData data = myScraper.scrape("https://www.amazon.co.uk/dp/B0925CM4BB");
//        System.out.println(data);
//    }
    @Override
    public Optional<String> productKey(String url) {
        Matcher matcher = ASIN_TOKEN.matcher(url);

        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }

        return Optional.empty();
    }
    /** Picks a random desktop user agent — a fixed one is an obvious bot signature. */
    private String randomUserAgent() {
        return USER_AGENTS.get(ThreadLocalRandom.current().nextInt(USER_AGENTS.size()));
    }

    /** Fetches an Amazon product page and extracts title, price, currency and image. */
    public ProductData scrape(String url) {
        String marketplaceId = marketplaces.idFor(url);
        ScrapeProperties.MarketplaceConfig marketplace = marketplaces.configFor(marketplaceId);
        Fetched fetched = fetch(url, marketplaceId, marketplace);
        Document document = fetched.document();

        if (document.html().contains("validateCaptcha")) {
            throw new ScrapeException("Amazon blocked request with CAPTCHA");
        }

        requireExpectedHost(fetched.finalUrl(), marketplace, url);

        String title = findFirstMatch(document, TITLE_SELECTORS, false);
        if (title != null) {
            title = title.trim();
        }
        if (title == null || title.isBlank()) {
            throw new ScrapeException("No product title on page — not a product page: " + url);
        }

        requireExpectedAsin(document, url);

        String imageUrl = findFirstMatch(document, IMAGE_SELECTORS, true);

        if (isExplicitlyUnavailable(document)) {
            return new ProductData(title, null, null, imageUrl, Availability.UNAVAILABLE);
        }

        String rawPrice = findPrice(document);
        if (rawPrice == null) {
            if (hasBuyButton(document)) {
                // Buyable but unreadable: the page shape changed. A real failure.
                throw new ScrapeException("Offer present but no price element matched for URL: " + url);
            }
            if (hasAnyPriceElement(document)) {
                throw new ScrapeException("No buy option and no readable product price for URL: " + url);
            }
            log.debug("No offer markers and no price elements at all — treating as unavailable: {}", url);
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
                    .header("Accept-Encoding", "gzip, deflate, br")
                    .header("Cache-Control", "no-cache")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")
                    .cookieStore(cookies) // get cookies
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

    private void requireExpectedAsin(Document doc, String requestedUrl) {
        Optional<String> requested = productKey(requestedUrl);
        if (requested.isEmpty()) return;

        String onPage = pageAsin(doc);
        if (onPage == null) {
            log.debug("No ASIN element on page for {} — identity check skipped", requestedUrl);
            return;
        }
        if (!onPage.equalsIgnoreCase(requested.get())) {
            throw new ScrapeException("Page for ASIN " + onPage + " was returned for requested ASIN "
                    + requested.get() + " (" + requestedUrl + ")");
        }
    }

    /** The ASIN the page claims to be for, or null when it does not say. */
    private String pageAsin(Document doc) {
        for (String selector : PAGE_ASIN_SELECTORS) {
            Element element = doc.selectFirst(selector);
            if (element == null) continue;
            // input#ASIN carries it as a form value, #ppd as a data attribute.
            String value = element.hasAttr("value")
                    ? element.attr("value")
                    : element.attr("data-csa-c-asin");
            if (!value.isBlank()) return value.trim();
        }
        return null;
    }

    private String findPrice(Document doc) {
        String label = findFirstMatch(doc, PRICE_LABEL_SELECTORS, false);
        if (label != null) {
            String token = firstPriceToken(label);
            if (token != null) {
                return token;
            }
        }
        for (String selector : PRICE_ELEMENT_SELECTORS) {
            for (Element el : doc.select(selector)) {
                String value = priceTextFrom(el);
                if (value != null) {
                    return value;
                }
            }
        }
        // Legacy id-based blocks, which hold the price as their own text.
        return findFirstMatch(doc, PRICE_SELECTORS, false);
    }

    private String firstPriceToken(String text) {
        Matcher matcher = PRICE_TOKEN.matcher(text);
        return matcher.find() ? matcher.group().trim() : null;
    }

    private String priceTextFrom(Element priceElement) {
        Element offscreen = priceElement.selectFirst(".a-offscreen");
        if (offscreen != null && !offscreen.text().isBlank()) {
            return offscreen.text();
        }

        Element symbol = priceElement.selectFirst(".a-price-symbol");
        Element whole = priceElement.selectFirst(".a-price-whole");
        Element fraction = priceElement.selectFirst(".a-price-fraction");
        if (whole == null) {
            return null;
        }
        // .a-price-whole contains its own decimal separator, e.g. "7."
        String assembled = (symbol == null ? "" : symbol.text())
                + whole.text()
                + (fraction == null ? "" : fraction.text());
        return assembled.isBlank() ? null : assembled;
    }

/** The retailer says outright that this cannot be bought here. */
    private boolean isExplicitlyUnavailable(Document doc) {
        for (String selector : UNAVAILABLE_SELECTORS) {
            if (doc.selectFirst(selector) != null) return true;
        }
        return false;
    }

    /** A live offer renders one of these. */
    private boolean hasBuyButton(Document doc) {
        for (String selector : PURCHASABLE_SELECTORS) {
            if (doc.selectFirst(selector) != null) return true;
        }
        return false;
    }

    private boolean hasAnyPriceElement(Document doc) {
        return doc.selectFirst(".a-price") != null;
    }

    private String findFirstMatch(Document doc, String[] selectors, boolean wantAttribute) {
        for (String selector : selectors) {
            for (Element el : doc.select(selector)) {
                String value = wantAttribute ? el.attr("src") : el.text();
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

        if (raw.contains("AED") || raw.contains("د.إ")) return "AED";

        if (raw.contains("£")) return "GBP";
        if (raw.contains("€")) return "EUR";
        if (raw.contains("¥")) return "JPY";
        if (raw.contains("$")) return "USD";

        return "UNKNOWN"; // unknown symbol
    }

}