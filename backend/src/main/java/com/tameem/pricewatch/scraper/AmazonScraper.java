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
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Component
public class AmazonScraper implements ProductScraper {

    private static final Logger log = LoggerFactory.getLogger(AmazonScraper.class);

    private final MarketplaceRegistry marketplaces;

    public AmazonScraper(MarketplaceRegistry marketplaces) {
        this.marketplaces = marketplaces;
    }

    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    );

    /**
     * Ordered most specific first. Every selector is scoped to the main product
     * column: an unscoped `.a-price` matches recommendation carousels too, and
     * `selectFirst` would then return a different product's price. Amazon renames
     * these containers over time, so several generations are listed.
     */
    /**
     * Accessibility label carrying the price as plain text, e.g.
     * "£7.73 with 40 percent savings". Most reliable source when present.
     */
    /** Optional currency code or symbol, then a number: "£7.73", "AED 1,724.76". */
    private static final Pattern PRICE_TOKEN =
            Pattern.compile("(?:[A-Z]{2,3}|[^\\w\\s])\\s?\\d[\\d.,]*");

    private static final String[] PRICE_LABEL_SELECTORS = {
            "#apex-pricetopay-accessibility-label",
            "#corePriceDisplay_desktop_feature_div .aok-offscreen"
    };

    /**
     * Containers for the price itself. These select the {@code .a-price} element,
     * NOT its {@code .a-offscreen} child — see {@link #priceTextFrom} for why.
     */
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
            // Amazon labels price semantics on the span itself. Prefer the
            // explicit "price to pay" before falling back to positional guesses.
            "#corePriceDisplay_desktop_feature_div .apex-pricetopay-value .a-offscreen",
            ".priceToPay .a-offscreen",
            ".apex-pricetopay-value .a-offscreen",
            // Fallbacks, each excluding .a-text-price — see PRICE_EXCLUSION note.
            "#corePriceDisplay_desktop_feature_div .a-price:not(.a-text-price) .a-offscreen",
            "#corePrice_feature_div .a-price:not(.a-text-price) .a-offscreen",
            "#apex_desktop .a-price:not(.a-text-price) .a-offscreen",
            "#buybox .a-price:not(.a-text-price) .a-offscreen",
            "#priceblock_ourprice",
            "#priceblock_dealprice",
            // Widest net still inside the product column, never the whole page.
            "#centerCol .a-price:not(.a-text-price) .a-offscreen",
            "#ppd .a-price:not(.a-text-price) .a-offscreen"
    };

    /*
     * PRICE_EXCLUSION: `.a-text-price` marks a price that is NOT what you pay.
     * Amazon uses it for both `.apex-priceperunit-value` (e.g. AED44.68 per item
     * in an 11-piece set) and `.apex-basisprice-value` (the crossed-out "was"
     * price). Both sit inside the main price block and both render before the
     * real price in document order, so any selector that merely scopes to the
     * product column will pick one of them. Excluding the class is what makes
     * position irrelevant.
     */

    private static final String[] TITLE_SELECTORS = {
            "#productTitle"
    };

    /**
     * Presence of any of these means the retailer has no purchasable offer for
     * the requesting location. Checked before hunting for a price: without an
     * offer there is no price element, and searching on would only find prices
     * belonging to recommendation carousels.
     */
    private static final String[] UNAVAILABLE_SELECTORS = {
            "#outOfStock",
            "#exports_desktop_outOfStock_buybox"
    };
    // Deliberately NOT "#availability .a-color-price": that matches low-stock
    // urgency text such as "Only 1 left in stock", which means the opposite.

    /** A live offer always renders one of these. */
    private static final String[] PURCHASABLE_SELECTORS = {
            "#add-to-cart-button",
            "#buy-now-button"
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

    /** Picks a random desktop user agent — a fixed one is an obvious bot signature. */
    private String randomUserAgent() {
        return USER_AGENTS.get(ThreadLocalRandom.current().nextInt(USER_AGENTS.size()));
    }

    /** Fetches an Amazon product page and extracts title, price, currency and image. */
    public ProductData scrape(String url) {
        Document response = fetch(url, marketplaces.configFor(marketplaces.idFor(url)));

        if (response.html().contains("validateCaptcha")) {
            throw new ScrapeException("Amazon blocked request with CAPTCHA");
        }

        String title = findFirstMatch(response, TITLE_SELECTORS, false);
        if (title != null) {
            title = title.trim();
        }

        String imageUrl = findFirstMatch(response, IMAGE_SELECTORS, true);

        // Availability is decided before price, not inferred from its absence.
        // "No offer here" is a real observation; "markup changed" is a failure.
        // Conflating them is how carousel prices got recorded as product prices.
        if (!isPurchasable(response)) {
            return new ProductData(title, null, null, imageUrl, Availability.UNAVAILABLE);
        }

        String rawPrice = findPrice(response);
        if (rawPrice == null) {
            // Purchasable but no price found: the page shape changed. A real failure.
            throw new ScrapeException("Offer present but no price element matched for URL: " + url);
        }

        BigDecimal price = parsePrice(rawPrice);
        String currency = parseCurrency(rawPrice);

        return new ProductData(title, price, currency, imageUrl, Availability.AVAILABLE);
    }

    /**
     * Requests the page with browser-like headers, in the marketplace's locale and
     * (when configured) through a proxy in its country. Throws ScrapeException on
     * any network failure.
     */
    public Document fetch(String url, ScrapeProperties.MarketplaceConfig marketplace) {
        try {
            Connection connection = Jsoup.connect(url)
                    .userAgent(randomUserAgent())
                    .header("Accept",
                            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", marketplace.getAcceptLanguage())
                    .header("Accept-Encoding", "gzip, deflate, br")
                    .header("Cache-Control", "no-cache")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")
                    .timeout(10000);

            // Egress country decides which offers exist and in what currency, so a
            // marketplace with a proxy configured must not fall back to local egress:
            // the prices would be for the wrong country and look valid.
            if (marketplace.getProxyHost() != null && !marketplace.getProxyHost().isBlank()) {
                connection.proxy(marketplace.getProxyHost(), marketplace.getProxyPort());
            } else {
                log.debug("No proxy for delivery country {} — scraping from local egress",
                        marketplace.getDeliveryCountry());
            }

            return connection.get();
        } catch (IOException e) {
            throw new ScrapeException("Failed to fetch page: " + url, e);
        }
    }

    /**
     * Tries each selector in order and returns the first non-blank match.
     * If wantAttribute is true, extracts the "src" attribute (for images);
     * otherwise extracts text content.
     */
    /** Returns the first non-blank match across the selector list — layout varies, so order is fallback order. */
    /**
     * The offer price as raw text, or null if none could be read.
     *
     * <p>Tries the accessibility label first, then price containers in order of
     * specificity. Both routes exist because Amazon renders the price two
     * different ways on the same site.
     */
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

    /**
     * First monetary token in a sentence, with its symbol or code.
     *
     * <p>Accessibility labels read like "£7.73 with 40 percent savings". Handing
     * that to the parser, which strips non-digits, would yield 7.7340 — a
     * plausible-looking number that was never a price.
     */
    private String firstPriceToken(String text) {
        Matcher matcher = PRICE_TOKEN.matcher(text);
        return matcher.find() ? matcher.group().trim() : null;
    }

    /**
     * Reads a price out of an {@code .a-price} element.
     *
     * <p>Amazon renders these two ways. Usually {@code .a-offscreen} holds the
     * full string ("AED491.68") for screen readers, with a visually-styled copy
     * beside it. But on discounted listings the offscreen span is EMPTY and the
     * price exists only as separate symbol/whole/fraction spans. Reading only
     * {@code .a-offscreen} silently skips those — and since the crossed-out RRP
     * *does* populate its offscreen span, the fallback would land on the RRP and
     * record a price that was never charged.
     */
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

    /**
     * True when the page shows a live, purchasable offer. An explicit
     * out-of-stock marker wins over a buy button, since Amazon leaves stale
     * buttons in the markup on some layouts.
     */
    private boolean isPurchasable(Document doc) {
        for (String selector : UNAVAILABLE_SELECTORS) {
            if (doc.selectFirst(selector) != null) return false;
        }
        for (String selector : PURCHASABLE_SELECTORS) {
            if (doc.selectFirst(selector) != null) return true;
        }
        return false;
    }

    /**
     * First non-blank value across every selector, in order. Iterates all matches
     * of each selector rather than only the first: Amazon renders empty price
     * spans as placeholders, and stopping at the first match would abandon a
     * selector whose later matches hold the real value.
     */
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
        // Strip everything except digits, dot, comma - then normalise
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