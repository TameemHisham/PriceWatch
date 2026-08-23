package com.tameem.pricewatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

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
