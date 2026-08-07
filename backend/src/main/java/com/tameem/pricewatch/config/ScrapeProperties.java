package com.tameem.pricewatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scraper configuration, keyed by marketplace id (e.g. {@code AMAZON_UK}).
 *
 * <p>A marketplace is a storefront plus the delivery country it prices for.
 * "Amazon" is not a market: amazon.co.uk shipping to GB and amazon.ae shipping to
 * AE are different catalogues, currencies, and offers for the same product, so
 * prices are only comparable within one.
 *
 * <p>Held as configuration rather than an enum because adding a storefront is a
 * deployment concern, not a code change — Phase 4 adds several, and each is only
 * a host, a locale, and an egress route.
 *
 * <pre>
 * pricewatch.scrape.marketplaces.AMAZON_UK.host=amazon.co.uk
 * pricewatch.scrape.marketplaces.AMAZON_UK.delivery-country=GB
 * pricewatch.scrape.marketplaces.AMAZON_UK.accept-language=en-GB,en;q=0.9
 * pricewatch.scrape.marketplaces.AMAZON_UK.proxy-host=uk-proxy.example.net
 * pricewatch.scrape.marketplaces.AMAZON_UK.proxy-port=8080
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "pricewatch.scrape")
public class ScrapeProperties {

    /** Insertion-ordered so URL matching is deterministic when hosts overlap. */
    private Map<String, MarketplaceConfig> marketplaces = new LinkedHashMap<>();

    public Map<String, MarketplaceConfig> getMarketplaces() {
        return marketplaces;
    }

    public void setMarketplaces(Map<String, MarketplaceConfig> marketplaces) {
        this.marketplaces = marketplaces;
    }

    public static class MarketplaceConfig {
        /** Domain that identifies this storefront in a product URL. */
        private String host;
        /** ISO country this storefront is being priced for. */
        private String deliveryCountry;
        /** Sent as Accept-Language so the storefront does not guess locale from IP. */
        private String acceptLanguage = "en-GB,en;q=0.9";
        /**
         * Optional egress proxy, located in the delivery country. Retailers price
         * and stock by the requesting IP, so without one the observation reflects
         * whatever country this host happens to sit in.
         */
        private String proxyHost;
        private int proxyPort;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }

        public String getDeliveryCountry() { return deliveryCountry; }
        public void setDeliveryCountry(String deliveryCountry) { this.deliveryCountry = deliveryCountry; }

        public String getAcceptLanguage() { return acceptLanguage; }
        public void setAcceptLanguage(String acceptLanguage) { this.acceptLanguage = acceptLanguage; }

        public String getProxyHost() { return proxyHost; }
        public void setProxyHost(String proxyHost) { this.proxyHost = proxyHost; }

        public int getProxyPort() { return proxyPort; }
        public void setProxyPort(int proxyPort) { this.proxyPort = proxyPort; }
    }
}
