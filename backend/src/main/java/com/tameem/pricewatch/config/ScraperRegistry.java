package com.tameem.pricewatch.config;

import com.tameem.pricewatch.scraper.ProductScraper;
import com.tameem.pricewatch.scraper.UnsupportedMarketplaceException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves which scraper handles a marketplace or a product URL.
 * <p>
 * Replaces injecting a single {@link ProductScraper}, which only ever worked while exactly
 * one implementation existed on the context. Each scraper claims its own marketplace ids via
 * {@link ProductScraper#supports}, so adding a storefront is a new bean rather than an edit
 * to a central switch.
 */
@Component
public class ScraperRegistry {

    private final List<ProductScraper> scrapers;
    private final MarketplaceRegistry marketplaces;

    public ScraperRegistry(List<ProductScraper> scrapers, MarketplaceRegistry marketplaces) {
        this.scrapers = scrapers;
        this.marketplaces = marketplaces;
    }

    /** The scraper serving this marketplace id, or throws when none claims it. */
    public ProductScraper forMarketplace(String marketplaceId) {
        for (ProductScraper scraper : scrapers) {
            if (scraper.supports(marketplaceId)) {
                return scraper;
            }
        }
        throw new UnsupportedMarketplaceException(
                "No scraper is registered for marketplace '" + marketplaceId + "'");
    }

    /** The scraper serving the storefront this URL belongs to. */
    public ProductScraper forUrl(String url) {
        return forMarketplace(marketplaces.idFor(url));
    }

    /**
     * Whether two marketplaces are served by the same scraper, i.e. are storefronts of one
     * retailer. Used to keep same-product fan-out inside a retailer: an ASIN is meaningful
     * across amazon.co.uk/.ae/.com, and meaningless on any other store.
     */
    public boolean sameRetailer(String marketplaceA, String marketplaceB) {
        return forMarketplace(marketplaceA) == forMarketplace(marketplaceB);
    }
}
