package com.tameem.pricewatch.scraper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;


@Component
public class AmazonScraper implements ProductScraper {

    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    );

    private static final String[] PRICE_SELECTORS = {
            ".a-price .a-offscreen",
            "#corePrice_feature_div .a-price .a-offscreen",
            ".apex-core-price-identifier .apex-pricetopay-value .a-price .a-offscreen",
            "#priceblock_ourprice",
            "#priceblock_dealprice"
    };

    private static final String[] TITLE_SELECTORS = {
            "#productTitle"
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

    private String randomUserAgent() {
        return USER_AGENTS.get(ThreadLocalRandom.current().nextInt(USER_AGENTS.size()));
    }

    public ProductData scrape(String url) {
        Document response = fetch(url);

        if (response.html().contains("validateCaptcha")) {
            throw new ScrapeException("Amazon blocked request with CAPTCHA");
        }

        String rawPrice = findFirstMatch(response, PRICE_SELECTORS, false);
        if (rawPrice == null) {
            throw new ScrapeException("Could not locate price for URL: " + url);
        }

        String title = findFirstMatch(response, TITLE_SELECTORS, false);
        if (title != null) {
            title = title.trim();
        }

        String imageUrl = findFirstMatch(response, IMAGE_SELECTORS, true);

        BigDecimal price = parsePrice(rawPrice);
        String currency = parseCurrency(rawPrice);

        return new ProductData(title, price, currency, imageUrl);
    }

    public Document fetch(String url) {
        try {
            return Jsoup.connect(url)
                    .userAgent(randomUserAgent())
                    .header("Accept",
                            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-GB,en;q=0.9")
                    .header("Accept-Encoding", "gzip, deflate, br")
                    .header("Cache-Control", "no-cache")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")
                    .timeout(10000)
                    .get();
        } catch (IOException e) {
            throw new ScrapeException("Failed to fetch page: " + url, e);
        }
    }

    /**
     * Tries each selector in order and returns the first non-blank match.
     * If wantAttribute is true, extracts the "src" attribute (for images);
     * otherwise extracts text content.
     */
    private String findFirstMatch(Document doc, String[] selectors, boolean wantAttribute) {
        for (String selector : selectors) {
            Element el = doc.selectFirst(selector);
            if (el == null) continue;

            String value = wantAttribute ? el.attr("src") : el.text();
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }

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