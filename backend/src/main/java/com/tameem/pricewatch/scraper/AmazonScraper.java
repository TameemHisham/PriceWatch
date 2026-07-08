package com.tameem.pricewatch.scraper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;

@Component
public class AmazonScraper implements ProductScraper {

    // Amazon returns a bot/error page to Java's default UA ("Java/17..."),
    // so we pretend to be a real Chrome browser.
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    // The buy-box price lives in one of these containers. We look here first (in order)
    // so we don't accidentally grab a strikethrough list price or an "other sellers" price.
    private static final String[] PRICE_CONTAINERS = {
            "#corePriceDisplay_desktop_feature_div",
            "#corePrice_feature_div",
            "#apex_desktop",
            "#price_inside_buybox",
            "#booksHeaderSection"
    };

    @Override
    public String storeName() {
        return "Amazon";
    }

    @Override
    public boolean supports(String url) {
        if (url == null) return false;
        try {
            String host = new URI(url.trim()).getHost();
            String path = new URI(url.trim()).getPath();
            if (host == null || path == null) return false;
            host = host.toLowerCase();
            return host.contains("amazon.")
                    && (path.contains("/dp/") || path.contains("/gp/product/"));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    @Override
    public ProductData scrape(String url) {
        Document doc = fetch(url);

        String title = textOf(doc.selectFirst("#productTitle"));
        if (title == null) {
            throw new ScrapeException(
                    "No product title — Amazon likely served a bot/CAPTCHA page, not the product");
        }

        Element priceBlock = findPriceBlock(doc);
        BigDecimal price = parsePrice(priceBlock);
        String currency = parseCurrency(priceBlock);
        String imageUrl = attrOf(doc.selectFirst("#landingImage"), "src");

        return new ProductData(title, price, currency, imageUrl);
    }

    // --- fetching ---

    private Document fetch(String url) {
        try {
            return Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .header("Accept-Language", "en-GB,en;q=0.9")
                    .timeout(10_000)
                    .maxBodySize(0)   // 0 = unlimited; Amazon pages routinely exceed the 2MB default
                    .get();           // fetches the HTML AND parses it into a DOM tree
        } catch (IOException e) {
            throw new ScrapeException("Failed to fetch: " + url, e);
        }
    }

    // --- price (the one genuinely tricky bit) ---

    /**
     * Finds the {@code .a-price} element inside the first matching buy-box container,
     * so whole/fraction/symbol are all read from the SAME price block.
     */
    private Element findPriceBlock(Document doc) {
        for (String selector : PRICE_CONTAINERS) {
            Element container = doc.selectFirst(selector);
            if (container != null) {
                Element price = container.selectFirst(".a-price");
                if (price != null) return price;
            }
        }
        return doc.selectFirst(".a-price"); // last resort: first price anywhere
    }

    private BigDecimal parsePrice(Element priceBlock) {
        if (priceBlock == null) {
            throw new ScrapeException("No price element found on page");
        }

        // Preferred: Amazon hides a clean full price in .a-offscreen (for screen readers),
        // e.g. the text "£24.99". Easiest and most reliable source.
        Element offscreen = priceBlock.selectFirst(".a-offscreen");
        if (offscreen != null) {
            BigDecimal p = toBigDecimal(offscreen.text());
            if (p != null) return p;
        }

        // Fallback: the VISIBLE price is split across two spans —
        // .a-price-whole ("24") and .a-price-fraction ("99") — so we stitch them.
        // Both are read from the same price block above, not from anywhere on the page.
        Element whole = priceBlock.selectFirst(".a-price-whole");
        if (whole != null) {
            Element fraction = priceBlock.selectFirst(".a-price-fraction");
            String w = whole.text().replaceAll("[^0-9]", "");
            String f = fraction != null ? fraction.text().replaceAll("[^0-9]", "") : "00";
            if (!w.isBlank()) {
                return new BigDecimal(w + "." + f);
            }
        }

        throw new ScrapeException("No price element found on page");
    }

    /**
     * Locale-aware price parser. Works for both "£1,299.00" (dot decimal) and
     * "1.299,00 €" / "24,99" (comma decimal) without silently mangling the value.
     * Strategy: whichever of '.' or ',' appears last is the decimal separator; if
     * only one kind of separator is present, use the count of trailing digits to
     * decide whether it's a decimal point (1–2 digits) or a thousands group.
     */
    // Package-private (not private) purely so AmazonScraperTest can exercise the
    // locale edge cases directly — this is the trickiest bit of the scraper.
    BigDecimal toBigDecimal(String raw) {
        String s = raw.replaceAll("[^0-9.,]", "");
        if (s.isBlank()) return null;

        int lastDot = s.lastIndexOf('.');
        int lastComma = s.lastIndexOf(',');

        if (lastDot >= 0 && lastComma >= 0) {
            // Both present: the rightmost one is the decimal separator, the other groups thousands.
            char decimalSep = lastDot > lastComma ? '.' : ',';
            char groupSep = decimalSep == '.' ? ',' : '.';
            s = s.replace(String.valueOf(groupSep), "").replace(decimalSep, '.');
        } else if (lastDot >= 0 || lastComma >= 0) {
            char sep = lastDot >= 0 ? '.' : ',';
            int digitsAfter = s.length() - s.lastIndexOf(sep) - 1;
            if (digitsAfter == 1 || digitsAfter == 2) {
                s = s.replace(sep, '.');      // decimal separator (e.g. "24,99" -> "24.99")
            } else {
                s = s.replace(String.valueOf(sep), ""); // thousands group (e.g. "1,299" -> "1299")
            }
        }

        return s.isBlank() ? null : new BigDecimal(s);
    }

    // --- currency & helpers ---

    private String parseCurrency(Element priceBlock) {
        if (priceBlock != null) {
            Element symbol = priceBlock.selectFirst(".a-price-symbol");
            if (symbol != null) return symbol.text().trim();
        }
        return "UNKNOWN";
    }

    private String textOf(Element e) {
        return e != null ? e.text().trim() : null;
    }

    private String attrOf(Element e, String attr) {
        return e != null ? e.attr(attr) : null;
    }
}
