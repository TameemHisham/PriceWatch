package com.tameem.pricewatch.config;

import com.tameem.pricewatch.scraper.ScrapeException;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Resolves which configured marketplace a product URL belongs to. */
@Component
public class MarketplaceRegistry {

    private final ScrapeProperties properties;

    public MarketplaceRegistry(ScrapeProperties properties) {
        this.properties = properties;
    }

    /**
     * The marketplace id for a URL, e.g. {@code AMAZON_UK}.
     *
     * <p>Throws rather than defaulting: an unconfigured storefront would otherwise
     * be scraped and priced as if it were a different one, and the resulting rows
     * would look valid.
     */
    public String idFor(String url) {
        String lower = url.toLowerCase();
        for (Map.Entry<String, ScrapeProperties.MarketplaceConfig> entry
                : properties.getMarketplaces().entrySet()) {
            String host = entry.getValue().getHost();
            if (host != null && lower.contains(host.toLowerCase())) {
                return entry.getKey();
            }
        }
        throw new ScrapeException("No marketplace configured for URL: " + url);
    }

    /** Config for a marketplace id, or throws if it is no longer configured. */
    public ScrapeProperties.MarketplaceConfig configFor(String id) {
        ScrapeProperties.MarketplaceConfig config = properties.getMarketplaces().get(id);
        if (config == null) {
            // A listing stored under an id that configuration no longer defines.
            throw new ScrapeException("Marketplace '" + id + "' is not configured");
        }
        return config;
    }
}
