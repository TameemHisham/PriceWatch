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

    private static final Logger log = LoggerFactory.getLogger(AmazonScraper.class);

    private final MarketplaceRegistry marketplaces;

    /**
     * One cookie store per marketplace. {@link #fetch} builds a fresh connection on
     * every call, which throws away everything the storefront set — including the
     * session and locale cookies Amazon issues on first contact. A client that is
     * handed a cookie and never returns it looks less like a browser with every
     * request, and the storefront is free to answer with an interstitial instead of
     * the product.
     *
     * <p>Kept per marketplace and never shared: a cookie set by amazon.ae carries
     * that storefront's locale, and replaying it against amazon.co.uk would ask for
     * a different market than the one this listing claims to record.
     */
    private final Map<String, CookieStore> cookieStores = new ConcurrentHashMap<>();

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

    /**
     * Path case must be preserved; ASIN pattern expects uppercase characters.
     * **/
    private static final Pattern ASIN_TOKEN =
            Pattern.compile("/(?:dp|gp/product|gp/aw/d)/([A-Z0-9]{10})");

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

    /**
     * Where the page states which ASIN it is for. Both selectors are scoped to the
     * product block on purpose: a bare {@code [data-asin]} also matches every
     * recommendation carousel tile, so it finds *an* ASIN on any Amazon page and the
     * identity check would pass while looking at a completely different product.
     */
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

        // Everything below decides what a page MEANS. These three checks decide
        // whether it is the right page at all, and they run first for one reason: a
        // bot wall, a sign-in interstitial and a geo-redirect all parse cleanly and
        // all carry no price. Read in the old order they came out the far side as a
        // tidy UNAVAILABLE row — an observation asserting "checked, no offer here"
        // about a page that was never looked at.
        requireExpectedHost(fetched.finalUrl(), marketplace, url);

        String title = findFirstMatch(document, TITLE_SELECTORS, false);
        if (title != null) {
            title = title.trim();
        }
        // #productTitle renders on every real /dp/ page, out-of-stock ones included,
        // so its absence does not mean "no offer" — it means this is not a product
        // page. Cheapest of the three checks and it catches every interstitial
        // variant, including ones with no distinguishing markup of their own.
        if (title == null || title.isBlank()) {
            throw new ScrapeException("No product title on page — not a product page: " + url);
        }

        requireExpectedAsin(document, url);

        String imageUrl = findFirstMatch(document, IMAGE_SELECTORS, true);

        // Three outcomes, deliberately kept apart. Collapsing them is what let
        // carousel prices in, and what would later hide a markup change as a
        // stream of "no offer" observations.
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
                // Prices exist on the page but none in the product column, and no
                // way to buy. Ambiguous enough to be worth a human look.
                throw new ScrapeException("No buy option and no readable product price for URL: " + url);
            }
            // No buy option and no price element anywhere: unavailable variants are
            // rendered this way, without the #outOfStock marker a whole product gets.
            log.debug("No offer markers and no price elements at all — treating as unavailable: {}", url);
            return new ProductData(title, null, null, imageUrl, Availability.UNAVAILABLE);
        }

        BigDecimal price = parsePrice(rawPrice);
        String currency = parseCurrency(rawPrice);

        return new ProductData(title, price, currency, imageUrl, Availability.AVAILABLE);
    }

    /**
     * A fetched page together with the URL it actually came from.
     *
     * <p>The final URL is carried out of {@link #fetch} deliberately. jsoup follows
     * redirects silently, so the document alone cannot say whether it came from the
     * storefront that was asked for.
     */
    private record Fetched(Document document, URL finalUrl) {}

    /**
     * Requests the page with browser-like headers, in the marketplace's locale and
     * (when configured) through a proxy in its country. Throws ScrapeException on
     * any network failure.
     */

    private Fetched fetch(String url, String marketplaceId, ScrapeProperties.MarketplaceConfig marketplace) {
        try {
            CookieStore cookies = cookieStores.computeIfAbsent(
                    marketplaceId, id -> new CookieManager().getCookieStore());

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
                    .cookieStore(cookies)
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

            // execute() rather than get(): the response knows the URL it ended on
            // after redirects, and the document does not.
            Connection.Response response = connection.execute();
            return new Fetched(response.parse(), response.url());
        } catch (IOException e) {
            throw new ScrapeException("Failed to fetch page: " + url, e);
        }
    }

    /**
     * Fails when the page was served by a different storefront than the one asked
     * for.
     *
     * <p>Host only, never the path. Amazon rewrites paths and appends its own
     * {@code ref=} segments on nearly every request, so comparing paths would reject
     * good pages constantly. Subdomains pass: the configured host is
     * {@code amazon.co.uk} and the response lands on {@code www.amazon.co.uk}.
     */
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

    /**
     * Fails when the page is for a different product than the URL asked for.
     *
     * <p>Checked only when both sides state an ASIN. A missing page ASIN is logged
     * rather than thrown: these selectors are Amazon's markup, not a contract, and
     * turning one rename into a total scrape outage trades a rare wrong row for
     * guaranteed no rows. A mismatch is different — that is the page telling us
     * outright that it is not the product we asked for.
     */
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

    /**
     * Whether the page carries any price markup at all, anywhere — including
     * carousels. Used only to tell "this page has no prices on it" (an
     * unavailable variant) from "prices exist but not where we look" (our bug).
     */
    private boolean hasAnyPriceElement(Document doc) {
        return doc.selectFirst(".a-price") != null;
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