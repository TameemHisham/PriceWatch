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
 * eXtra (KSA) storefront scraper, following AmazonScraper's shape: fetch, verify the page
 * is really the product that was asked for, then extract.
 * <p>
 * Price is read from the schema.org JSON-LD offer and <strong>nowhere else</strong>: the
 * displayed price is client-rendered and appears nowhere in the served markup — not as
 * text, not in a data attribute — so there is no DOM fallback to degrade to. A missing or
 * unparseable offer throws.
 * <p>
 * Locale lives in the path ({@code /en-sa/}) and sets the currency, so canonicalUrl
 * preserves it rather than forcing one storefront's.
 */
@Component
public class ExtraScraper implements ProductScraper {

    private static final Logger log = LoggerFactory.getLogger(ExtraScraper.class);
    private final MarketplaceRegistry marketplaces;
    private final Map<String, CookieStore> cookieStores = new ConcurrentHashMap<>();

    public ExtraScraper(MarketplaceRegistry marketplaces) {
        this.marketplaces = marketplaces;
    }

    private static final String MARKETPLACE_ID = "EXTRA";

    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    );

    /**
     * eXtra's SKU trails /p/. Two shapes are in use: plain numeric ids for own stock
     * ("100383287") and an "MP"-prefixed form for marketplace sellers ("MP00023798").
     */
    private static final Pattern SKU_TOKEN = Pattern.compile("/p/([A-Z0-9]{5,})");

    /** Locale segment, e.g. "en-sa" or "ar-sa", which sets language and currency. */
    private static final Pattern LOCALE_TOKEN = Pattern.compile("^/([a-z]{2}-[a-z]{2})(?:/|$)");

    private static final String[] TITLE_SELECTORS = {
            "h1"
    };

    private static final String[] IMAGE_SELECTORS = {
            "meta[property=og:image]"
    };

    @Override
    public Store store() {
        return Store.EXTRA;
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
     * {@code https://host/{locale}/p/{sku}} — eXtra resolves a product from its SKU alone
     * (verified: the category path in front of /p/ can be omitted entirely), so the long
     * category slug is dropped and a re-categorisation upstream cannot orphan a listing.
     */
    @Override
    public String canonicalUrl(String url) {
        Optional<String> sku = productKey(url);
        if (sku.isEmpty()) {
            throw new ScrapeException("No eXtra SKU in URL: " + url);
        }
        String host = marketplaces.configFor(MARKETPLACE_ID).getHost();
        if (host == null || host.isBlank()) {
            throw new ScrapeException("Marketplace EXTRA has no configured host");
        }
        String qualified = host.startsWith("www.") ? host : "www." + host;
        try {
            String path = new URI(url).getPath();
            Matcher locale = LOCALE_TOKEN.matcher(path == null ? "" : path);
            if (!locale.find()) {
                throw new ScrapeException("No locale segment (e.g. /en-sa) in eXtra URL: " + url);
            }
            return "https://" + qualified + "/" + locale.group(1) + "/p/" + sku.get();
        } catch (URISyntaxException e) {
            throw new ScrapeException("Unparseable URL: " + url, e);
        }
    }

    /** Picks a random desktop user agent — a fixed one is an obvious bot signature. */
    private String randomUserAgent() {
        return USER_AGENTS.get(ThreadLocalRandom.current().nextInt(USER_AGENTS.size()));
    }

    /** Fetches an eXtra product page and extracts title, price, currency and image. */
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
            throw new ScrapeException("eXtra blocked request with a bot challenge");
        }

        requireExpectedHost(finalUrl, marketplace, requestedUrl);

        Optional<JsonLdProduct> jsonLd = JsonLdProduct.from(document);

        // Prefer the ld+json name here: eXtra's only h1 is a hidden SEO heading that appends
        // the site name ("… - M/L - eXtra"), which would end up as the stored product title.
        String title = jsonLd.map(JsonLdProduct::name).filter(n -> n != null && !n.isBlank())
                .orElseGet(() -> findFirstMatch(document, TITLE_SELECTORS));
        if (title != null) {
            title = title.trim();
        }
        if (title == null || title.isBlank()) {
            throw new ScrapeException("No product title on page — not a product page: " + requestedUrl);
        }

        requireExpectedSku(document, jsonLd.orElse(null), requestedUrl);

        String imageUrl = findImage(document, jsonLd.orElse(null));

        // The offer is the only accepted price source for this store.
        if (jsonLd.isEmpty()) {
            throw new ScrapeException("No schema.org Product on page, and eXtra prices are only "
                    + "read from ld+json — the displayed price is client-rendered and appears "
                    + "nowhere in the served markup: " + requestedUrl);
        }

        JsonLdProduct product = jsonLd.get();
        if (!product.isPurchasable()) {
            log.debug("ld+json availability {} for {} — treating as unavailable",
                    product.availability(), requestedUrl);
            return new ProductData(title, null, null, imageUrl, Availability.UNAVAILABLE);
        }
        if (!product.hasPrice()) {
            throw new ScrapeException("ld+json offer carried no price, and eXtra prices are not "
                    + "read from the DOM: " + requestedUrl);
        }

        String currency = product.currency() == null || product.currency().isBlank()
                ? "UNKNOWN"
                : product.currency();
        return new ProductData(title, product.price(), currency, imageUrl, Availability.AVAILABLE);
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
     * eXtra served all three rotated user agents cleanly during recon, so these markers are
     * the generic vendor interstitials rather than an observed eXtra block page.
     */
    private boolean isBotChallenge(Document doc) {
        if (doc.selectFirst("#px-captcha") != null) return true;
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
}
