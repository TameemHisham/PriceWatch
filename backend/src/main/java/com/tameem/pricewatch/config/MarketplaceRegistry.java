package com.tameem.pricewatch.config;

import com.tameem.pricewatch.scraper.ScrapeException;
import com.tameem.pricewatch.scraper.UnsupportedMarketplaceException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** Resolves which configured marketplace a product URL belongs to. */
@Component
public class MarketplaceRegistry {

    private final ScrapeProperties properties;

    public MarketplaceRegistry(ScrapeProperties properties) {
        this.properties = properties;
    }

    public String idFor(String url) {
        String lower = url.toLowerCase();
        for (Map.Entry<String, ScrapeProperties.MarketplaceConfig> entry
                : properties.getMarketplaces().entrySet()) {
            String host = entry.getValue().getHost();
            if (host != null && lower.contains(host.toLowerCase())) {
                return entry.getKey();
            }
        }
        throw new UnsupportedMarketplaceException(
                "This storefront is not supported yet: " + hostOf(url)
                        + ". Supported: " + properties.getMarketplaces().keySet());
    }

    /** Host portion of a URL, for error messages — never the whole URL, which is noisy. */
    private String hostOf(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? url : host;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }
    public Set<String> allMarketplaceIds() {
        return properties.getMarketplaces().keySet();
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
